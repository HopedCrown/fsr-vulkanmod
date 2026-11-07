package net.fsrvulkanmod.mixin;

import net.fsrvulkanmod.FsrVulkanModClient;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.framebuffer.Framebuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Targets net.vulkanmod.vulkan.Renderer — CONFIRMED real class, and
// endFrame()V is a CONFIRMED real public method on it (both verified by
// parsing the actual VulkanMod jar's method table, not guessed). endFrame()
// is the right injection point: by its name and its position among
// Renderer's other confirmed methods (beginFrame, beginMainRenderPass,
// endFrame, submitFrame, flushCmds — in that declared order), it runs after
// VulkanMod's world/terrain pass work and before the frame is submitted,
// which is exactly where our EASU+RCAS compute dispatches need to be
// recorded: after the (now shrunk, via InternalResolutionMixin) world render,
// before presentation.
@Mixin(Renderer.class)
public abstract class RendererMixin {

    @Inject(method = "endFrame", at = @At("TAIL"), remap = false)
    private void fsrVulkanMod$recordUpscale(CallbackInfo ci) {
        if (FsrVulkanModClient.upscalePass() == null || FsrVulkanModClient.config() == null) {
            return;
        }
        if (!FsrVulkanModClient.config().enabled) {
            return;
        }

        // Confirmed real path: Renderer.getInstance().getMainPass() returns
        // the active MainPass; getMainFramebuffer() returns its Framebuffer;
        // getWidth()/getHeight() are both confirmed real methods on
        // Framebuffer. With InternalResolutionMixin forcing DefaultMainPass
        // to own a separate, shrunk Framebuffer, these now report the real
        // internal (360p/720p-based) render size instead of the full window
        // size.
        Framebuffer mainFramebuffer = Renderer.getInstance().getMainPass().getMainFramebuffer();
        int srcWidth = mainFramebuffer.getWidth();
        int srcHeight = mainFramebuffer.getHeight();

        // The real output size is Minecraft's own window — this is vanilla's
        // accessor, not VulkanMod's, so it needs no further verification.
        var window = net.minecraft.client.Minecraft.getInstance().getWindow();
        int dstWidth = window.getWidth();
        int dstHeight = window.getHeight();

        FsrVulkanModClient.upscalePass().recordFrame(
                FsrVulkanModClient.config(), srcWidth, srcHeight, dstWidth, dstHeight);
    }
}
