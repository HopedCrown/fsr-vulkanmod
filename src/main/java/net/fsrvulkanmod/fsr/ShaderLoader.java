package net.fsrvulkanmod.fsr;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.vulkan.VK10.*;

// Loads a compiled SPIR-V (.spv) file from our mod's resources and wraps it
// in a VkShaderModule. VulkanMod almost certainly already has an equivalent
// utility (it has to load its own terrain/entity shaders somehow); reusing
// that instead of this standalone copy is preferable once its real class
// name is confirmed, since it may apply extra validation/caching we'd
// otherwise duplicate. This class exists so the mod compiles and runs on its
// own in the meantime.
public final class ShaderLoader {

    private ShaderLoader() {}

    // Reads "/assets/fsrvulkanmod/shaders/<fileName>" from the mod jar and
    // creates a VkShaderModule from its raw SPIR-V bytes.
    public static long loadComputeModule(VkDevice device, String fileName) {
        // SPIR-V binaries are produced from the .comp GLSL sources at build
        // time — add a Gradle task invoking glslc (part of the Vulkan SDK) to
        // compile fsr_easu.comp / fsr_rcas.comp into fsr_easu.comp.spv /
        // fsr_rcas.comp.spv before this loader runs; see VulkanMod's own
        // build.gradle for the pattern it uses for its shaders.
        String resourcePath = "/assets/fsrvulkanmod/shaders/" + fileName;
        byte[] spirvBytes = readResourceBytes(resourcePath);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            // Copies the raw bytes into a native (off-heap) buffer, which is
            // what LWJGL's Vulkan bindings require for the shader code pointer.
            ByteBuffer code = stack.malloc(spirvBytes.length);
            code.put(spirvBytes).flip();

            VkShaderModuleCreateInfo createInfo = VkShaderModuleCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO)
                    .pCode(code);

            LongBuffer pModule = stack.mallocLong(1);
            int result = vkCreateShaderModule(device, createInfo, null, pModule);
            if (result != VK_SUCCESS) {
                throw new RuntimeException("Failed to create shader module for " + fileName + ": " + result);
            }
            return pModule.get(0);
        }
    }

    // Reads an entire classpath resource into a byte array.
    private static byte[] readResourceBytes(String resourcePath) {
        try (InputStream in = ShaderLoader.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IOException("Resource not found: " + resourcePath);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new RuntimeException("Could not read shader resource " + resourcePath, e);
        }
    }
}
