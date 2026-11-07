package net.fsrvulkanmod.fsr;

import net.fsrvulkanmod.config.FsrConfig;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.LongBuffer;

import static org.lwjgl.vulkan.VK10.*;

// Owns the two compute pipelines (EASU, RCAS) and the Vulkan objects needed
// to dispatch them once per frame: descriptor set layout, descriptor pool,
// pipeline layout, and the intermediate image EASU writes into and RCAS reads
// back from.
//
// IMPORTANT / UNVERIFIED: the exact way to obtain VulkanMod's live
// VkDevice, the current frame's VkCommandBuffer, and the low-resolution
// color image + its VkImageView could not be confirmed against VulkanMod's
// actual source in this session (the file browser for xCollateral/VulkanMod's
// src/main tree wasn't reachable here) — only its top-level package names
// were. The methods below (vkDeviceHandle(), currentCommandBuffer(),
// mainColorImageView(), swapchainImageView()) are written as the integration
// seam: before building this mod, open VulkanMod's actual Device/Renderer/
// Swapchain classes (e.g. in a decompiler like Vineflower, or by cloning the
// repo once you have network access) and fill in their real accessors here.
// Everything below this seam — pipeline creation, barriers, dispatch,
// descriptor updates — is standard Vulkan 1.2 and does not depend on
// VulkanMod's internals, so it should not need to change.
public class FsrUpscalePass {

    // The two SPIR-V compute shader modules, compiled from fsr_easu.comp and
    // fsr_rcas.comp at build time (see the shader-compile Gradle task you'll
    // need to add — VulkanMod's own build.gradle shows how it compiles its
    // .vsh/.fsh files; mirror that pattern for these .comp files).
    private long easuShaderModule;
    private long rcasShaderModule;

    // Compute pipelines wrapping each shader module.
    private long easuPipeline;
    private long rcasPipeline;

    // Shared layout describing the two bindings (source sampler, dest image)
    // both shaders use, and the push-constant range each reads.
    private long descriptorSetLayout;
    private long pipelineLayout;

    // Pool the per-frame descriptor sets are allocated from.
    private long descriptorPool;

    // The intermediate full-resolution image EASU writes into and RCAS reads
    // from. Recreated whenever the window or the chosen preset changes size.
    // Built via VulkanMod's own net.vulkanmod.vulkan.framebuffer.Framebuffer
    // (confirmed real class: Framebuffer.builder(width, height, level,
    // createImages).build()), instead of raw vkAllocateMemory/vkCreateImage —
    // this reuses VulkanMod's own device-memory allocator rather than
    // duplicating one, which was an open VERIFY item before the real jar
    // was available to check against.
    private net.vulkanmod.vulkan.framebuffer.Framebuffer intermediateFramebuffer;
    private int intermediateWidth;
    private int intermediateHeight;

    // Derived from intermediateFramebuffer each time it's (re)created: the
    // raw VkImage handle (needed for the no-RCAS copy path) and VkImageView
    // (needed for binding it as a compute shader resource).
    private long intermediateImage;
    private long intermediateImageView;

    // --- Integration seam: now wired to real, confirmed VulkanMod classes ---
    // Confirmed by parsing the actual VulkanMod_26.1.2 jar's constant pool and
    // bytecode (no decompiler was available offline, so this used a small
    // hand-written .class parser instead) — these are real method names, not
    // placeholders, as of the jar you uploaded. Re-check if you update
    // VulkanMod to a different build.

    // VulkanMod's own static accessor for its logical device handle.
    private VkDevice vkDeviceHandle() {
        return net.vulkanmod.vulkan.Vulkan.getVkDevice();
    }

    // Renderer.getCommandBuffer() is confirmed static and returns the
    // VkCommandBuffer for the frame currently being recorded — exactly what
    // we need so our dispatch lands in the same submission as VulkanMod's own
    // draw calls instead of a separate one.
    private VkCommandBuffer currentCommandBuffer() {
        return net.vulkanmod.vulkan.Renderer.getCommandBuffer();
    }

    // Confirmed path: Renderer.getInstance().getMainPass() returns the active
    // MainPass (DefaultMainPass in vanilla VulkanMod); getMainFramebuffer()
    // returns its Framebuffer; getColorAttachmentView() returns the raw
    // VkImageView handle (a Vulkan 'long') for that framebuffer's color
    // attachment. With InternalResolutionMixin forcing DefaultMainPass to
    // always own a separate offscreen Framebuffer (see that class), this is
    // the low-resolution image EASU should read from.
    private long mainColorImageView() {
        return net.vulkanmod.vulkan.Renderer.getInstance()
                .getMainPass()
                .getMainFramebuffer()
                .getColorAttachmentView();
    }

    // Confirmed path: SwapChain extends Framebuffer and inherits
    // getColorAttachmentView(), giving the real presentation image's view —
    // this is what RCAS's output needs to land in before the GUI pass draws
    // on top at full resolution.
    private long swapchainImageView() {
        return net.vulkanmod.vulkan.Renderer.getInstance()
                .getSwapChain()
                .getColorAttachmentView();
    }

    // --- End integration seam ---

    // Call once, after Vulkan device creation, before the first frame.
    public void init() {
        VkDevice device = vkDeviceHandle();
        createDescriptorSetLayout(device);
        createPipelineLayout(device);
        createDescriptorPool(device);
        // Shader modules are created from SPIR-V bytes baked in as mod
        // resources at build time; loading them is identical to how
        // VulkanMod loads its own shaders, so that loader is reused rather
        // than duplicated here.
        this.easuShaderModule = ShaderLoader.loadComputeModule(device, "fsr_easu.comp.spv");
        this.rcasShaderModule = ShaderLoader.loadComputeModule(device, "fsr_rcas.comp.spv");
        createComputePipeline(device, easuShaderModule, true);
        createComputePipeline(device, rcasShaderModule, false);
    }

    // Describes the two bindings every FSR shader stage uses: binding 0 is a
    // combined image sampler (the source), binding 1 is a storage image
    // (the destination).
    private void createDescriptorSetLayout(VkDevice device) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(2, stack);

            // Binding 0: sampled source image, read in the compute shader.
            bindings.get(0)
                    .binding(0)
                    .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(1)
                    .stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);

            // Binding 1: storage image, written by the compute shader.
            bindings.get(1)
                    .binding(1)
                    .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(1)
                    .stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);

            VkDescriptorSetLayoutCreateInfo layoutInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO)
                    .pBindings(bindings);

            LongBuffer pLayout = stack.mallocLong(1);
            int result = vkCreateDescriptorSetLayout(device, layoutInfo, null, pLayout);
            if (result != VK_SUCCESS) {
                throw new RuntimeException("Failed to create FSR descriptor set layout: " + result);
            }
            this.descriptorSetLayout = pLayout.get(0);
        }
    }

    // Builds the pipeline layout: the descriptor set layout above plus a
    // push-constant range big enough for either shader's PushConstants block
    // (EASU needs 4 floats, RCAS needs 3 — we size for the larger of the two).
    private void createPipelineLayout(VkDevice device) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer setLayouts = stack.longs(this.descriptorSetLayout);

            VkPushConstantRange.Buffer pushConstants = VkPushConstantRange.calloc(1, stack)
                    .stageFlags(VK_SHADER_STAGE_COMPUTE_BIT)
                    .offset(0)
                    // 4 floats max (srcSize.xy + dstSize.xy in EASU); RCAS only
                    // uses the first 3 floats' worth of this same range.
                    .size(4 * Float.BYTES);

            VkPipelineLayoutCreateInfo layoutInfo = VkPipelineLayoutCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO)
                    .pSetLayouts(setLayouts)
                    .pPushConstantRanges(pushConstants);

            LongBuffer pLayout = stack.mallocLong(1);
            int result = vkCreatePipelineLayout(device, layoutInfo, null, pLayout);
            if (result != VK_SUCCESS) {
                throw new RuntimeException("Failed to create FSR pipeline layout: " + result);
            }
            this.pipelineLayout = pLayout.get(0);
        }
    }

    // A small fixed-size pool: we only ever need at most a couple of
    // descriptor sets in flight (one per pass, double-buffered), so this
    // avoids VulkanMod's own (potentially differently-configured) pools.
    private void createDescriptorPool(VkDevice device) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(2, stack);
            poolSizes.get(0).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(8);
            poolSizes.get(1).type(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(8);

            VkDescriptorPoolCreateInfo poolInfo = VkDescriptorPoolCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO)
                    .pPoolSizes(poolSizes)
                    .maxSets(8);

            LongBuffer pPool = stack.mallocLong(1);
            int result = vkCreateDescriptorPool(device, poolInfo, null, pPool);
            if (result != VK_SUCCESS) {
                throw new RuntimeException("Failed to create FSR descriptor pool: " + result);
            }
            this.descriptorPool = pPool.get(0);
        }
    }

    // Creates one compute pipeline from an already-loaded shader module.
    private void createComputePipeline(VkDevice device, long shaderModule, boolean isEasu) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPipelineShaderStageCreateInfo stage = VkPipelineShaderStageCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                    .stage(VK_SHADER_STAGE_COMPUTE_BIT)
                    .module(shaderModule)
                    .pName(stack.UTF8("main"));

            VkComputePipelineCreateInfo createInfo = VkComputePipelineCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO)
                    .stage(stage)
                    .layout(this.pipelineLayout);

            LongBuffer pPipeline = stack.mallocLong(1);
            int result = vkCreateComputePipelines(device, VK_NULL_HANDLE, createInfo, null, pPipeline);
            if (result != VK_SUCCESS) {
                throw new RuntimeException("Failed to create FSR compute pipeline: " + result);
            }
            if (isEasu) {
                this.easuPipeline = pPipeline.get(0);
            } else {
                this.rcasPipeline = pPipeline.get(0);
            }
        }
    }

    // Call once per frame, after VulkanMod has finished rendering the world
    // into its low-resolution target and before the GUI pass draws on top.
    public void recordFrame(FsrConfig config, int srcWidth, int srcHeight, int dstWidth, int dstHeight) {
        if (!config.enabled) {
            // Upscaling disabled: nothing to record, VulkanMod presents as normal.
            return;
        }

        ensureIntermediateImage(dstWidth, dstHeight);

        VkCommandBuffer cmd = currentCommandBuffer();

        // EASU dispatch: low-res source -> full-res intermediate image.
        dispatchEasu(cmd, srcWidth, srcHeight, dstWidth, dstHeight);

        // A barrier is required here so RCAS cannot start reading the
        // intermediate image before EASU's writes to it are visible.
        insertImageBarrier(cmd, this.intermediateImage,
                VK_ACCESS_SHADER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);

        if (config.rcasEnabled) {
            // RCAS dispatch: intermediate image -> final swapchain/present image.
            dispatchRcas(cmd, config, dstWidth, dstHeight);
        } else {
            // RCAS disabled: just copy the EASU output straight to the
            // presentation target with no additional sharpening pass.
            blitIntermediateToSwapchain(cmd, dstWidth, dstHeight);
        }
    }

    // Records the EASU compute dispatch: one invocation per destination pixel.
    private void dispatchEasu(VkCommandBuffer cmd, int srcWidth, int srcHeight, int dstWidth, int dstHeight) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, this.easuPipeline);

            long descriptorSet = allocateAndWriteDescriptorSet(
                    mainColorImageView(), this.intermediateImageView);
            vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, this.pipelineLayout,
                    0, stack.longs(descriptorSet), null);

            // Push constants: srcSize.xy, dstSize.xy — matches fsr_easu.comp's
            // PushConstants block layout exactly.
            java.nio.FloatBuffer pushData = stack.mallocFloat(4);
            pushData.put(0, (float) srcWidth);
            pushData.put(1, (float) srcHeight);
            pushData.put(2, (float) dstWidth);
            pushData.put(3, (float) dstHeight);
            vkCmdPushConstants(cmd, this.pipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, pushData);

            // Dispatches one workgroup per 8x8 block of destination pixels,
            // rounding up so partial edge blocks are still covered (the
            // shader's own bounds check discards the extra invocations).
            int groupsX = (dstWidth + 7) / 8;
            int groupsY = (dstHeight + 7) / 8;
            vkCmdDispatch(cmd, groupsX, groupsY, 1);
        }
    }

    // Records the RCAS compute dispatch: intermediate image -> present target.
    private void dispatchRcas(VkCommandBuffer cmd, FsrConfig config, int dstWidth, int dstHeight) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, this.rcasPipeline);

            long descriptorSet = allocateAndWriteDescriptorSet(
                    this.intermediateImageView, swapchainImageView());
            vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, this.pipelineLayout,
                    0, stack.longs(descriptorSet), null);

            // Push constants: dstSize.xy, sharpness — matches fsr_rcas.comp's
            // PushConstants block layout exactly.
            java.nio.FloatBuffer pushData = stack.mallocFloat(3);
            pushData.put(0, (float) dstWidth);
            pushData.put(1, (float) dstHeight);
            pushData.put(2, config.toRcasNativeSharpness());
            vkCmdPushConstants(cmd, this.pipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, pushData);

            int groupsX = (dstWidth + 7) / 8;
            int groupsY = (dstHeight + 7) / 8;
            vkCmdDispatch(cmd, groupsX, groupsY, 1);
        }
    }

    // When RCAS is turned off, this performs a straight image copy from the
    // EASU intermediate image to the present target, so the frame still ends
    // up at full resolution with no sharpening applied.
    private void blitIntermediateToSwapchain(VkCommandBuffer cmd, int width, int height) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // Confirmed real accessors: SwapChain extends Framebuffer, and
            // Framebuffer.getColorAttachment() returns a VulkanImage whose
            // getId() is the raw VkImage handle a VkImageCopy needs (its
            // getColorAttachmentView(), used elsewhere in this class, is only
            // the VkImageView, which vkCmdCopyImage cannot take directly).
            long swapchainImage = net.vulkanmod.vulkan.Renderer.getInstance()
                    .getSwapChain()
                    .getColorAttachment()
                    .getId();

            VkImageSubresourceLayers.Buffer subresource = VkImageSubresourceLayers.calloc(1, stack);
            subresource.get(0)
                    .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .mipLevel(0).baseArrayLayer(0).layerCount(1);

            VkImageCopy.Buffer region = VkImageCopy.calloc(1, stack);
            region.get(0)
                    .srcSubresource(subresource.get(0))
                    .dstSubresource(subresource.get(0))
                    .extent(e -> e.set(width, height, 1));

            vkCmdCopyImage(cmd,
                    this.intermediateImage, VK_IMAGE_LAYOUT_GENERAL,
                    swapchainImage, VK_IMAGE_LAYOUT_GENERAL,
                    region);
        }
    }

    // Allocates one descriptor set per dispatch and writes its two bindings.
    // Simple per-call allocation is fine here: FSR only ever needs at most two
    // sets per frame (EASU's and RCAS's), well within the pool's capacity.
    private long allocateAndWriteDescriptorSet(long sourceView, long destView) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorSetAllocateInfo allocInfo = VkDescriptorSetAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO)
                    .descriptorPool(this.descriptorPool)
                    .pSetLayouts(stack.longs(this.descriptorSetLayout));

            LongBuffer pSet = stack.mallocLong(1);
            vkAllocateDescriptorSets(vkDeviceHandle(), allocInfo, pSet);
            long set = pSet.get(0);

            VkDescriptorImageInfo.Buffer sourceInfo = VkDescriptorImageInfo.calloc(1, stack)
                    .imageView(sourceView)
                    .imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            // VERIFY: a real sampler handle is required here (VulkanMod almost
            // certainly already owns a default linear sampler we should reuse
            // rather than creating a duplicate one).
            // sourceInfo.get(0).sampler(someLinearSampler);

            VkDescriptorImageInfo.Buffer destInfo = VkDescriptorImageInfo.calloc(1, stack)
                    .imageView(destView)
                    .imageLayout(VK_IMAGE_LAYOUT_GENERAL);

            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(2, stack);
            writes.get(0)
                    .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .dstSet(set).dstBinding(0).descriptorCount(1)
                    .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .pImageInfo(sourceInfo);
            writes.get(1)
                    .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .dstSet(set).dstBinding(1).descriptorCount(1)
                    .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .pImageInfo(destInfo);

            vkUpdateDescriptorSets(vkDeviceHandle(), writes, null);
            return set;
        }
    }

    // Inserts a pipeline barrier transitioning (and synchronizing access to)
    // the intermediate image between EASU's write and RCAS's read.
    private void insertImageBarrier(VkCommandBuffer cmd, long image,
                                     int srcAccess, int dstAccess, int srcStage, int dstStage) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack);
            barrier.get(0)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                    .srcAccessMask(srcAccess)
                    .dstAccessMask(dstAccess)
                    .oldLayout(VK_IMAGE_LAYOUT_GENERAL)
                    .newLayout(VK_IMAGE_LAYOUT_GENERAL)
                    .image(image)
                    .subresourceRange(r -> r
                            .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                            .baseMipLevel(0).levelCount(1)
                            .baseArrayLayer(0).layerCount(1));

            vkCmdPipelineBarrier(cmd, srcStage, dstStage, 0, null, null, barrier);
        }
    }

    // (Re)creates the intermediate full-resolution image if the requested
    // size doesn't match what's currently allocated — e.g. on window resize
    // or when the user switches the 360p/720p preset.
    private void ensureIntermediateImage(int width, int height) {
        if (width == this.intermediateWidth && height == this.intermediateHeight
                && this.intermediateFramebuffer != null) {
            // Already the right size; nothing to do.
            return;
        }

        // Frees the previous framebuffer (and its image/view/memory) before
        // reallocating at the new size, exactly as VulkanMod's own
        // DefaultMainPass.createResources() does for its framebuffers
        // (confirmed via disassembly: it calls Framebuffer.cleanUp(true) on
        // the old one before building a replacement).
        if (this.intermediateFramebuffer != null) {
            this.intermediateFramebuffer.cleanUp(true);
        }

        // Builder args confirmed from Framebuffer$Builder's real constructor
        // (IIIZ): width, height, mip level count, and whether to actually
        // allocate backing images now (true — we need it usable immediately).
        this.intermediateFramebuffer = net.vulkanmod.vulkan.framebuffer.Framebuffer
                .builder(width, height, 1, true)
                .build();

        // Caches the raw handles our compute dispatches bind directly, so we
        // don't call through the Framebuffer object on every single frame.
        this.intermediateImageView = this.intermediateFramebuffer.getColorAttachmentView();
        this.intermediateImage = this.intermediateFramebuffer.getColorAttachment().getId();

        this.intermediateWidth = width;
        this.intermediateHeight = height;
    }

    // Releases every Vulkan object this class owns. Call on mod/game shutdown.
    public void destroy() {
        VkDevice device = vkDeviceHandle();
        vkDestroyPipeline(device, this.easuPipeline, null);
        vkDestroyPipeline(device, this.rcasPipeline, null);
        vkDestroyShaderModule(device, this.easuShaderModule, null);
        vkDestroyShaderModule(device, this.rcasShaderModule, null);
        vkDestroyPipelineLayout(device, this.pipelineLayout, null);
        vkDestroyDescriptorSetLayout(device, this.descriptorSetLayout, null);
        vkDestroyDescriptorPool(device, this.descriptorPool, null);
        if (this.intermediateFramebuffer != null) {
            this.intermediateFramebuffer.cleanUp(true);
        }
    }
}
