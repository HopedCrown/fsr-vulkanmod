package net.fsrvulkanmod.fsr;

import dev.isxander.yacl3.api.NameableEnum;
import net.minecraft.network.chat.Component;

// This enum is the single source of truth for every selectable render-resolution
// preset. The config screen lists these by their displayName, and the upscale
// pass reads their scaleFactor to compute the internal render target size.
// Implements YACL's NameableEnum so the EnumControllerBuilder in FsrYaclScreen
// can show 'displayName' directly instead of the raw Java constant name.
public enum FsrRenderPreset implements NameableEnum {

    // Renders at roughly 360p vertically, then FSR-upscales to the window size.
    // Biggest FPS gain, softest image — good for low-end iGPUs like the i5-1035G1
    // when you need the largest possible frame-time win.
    P360("360p (max performance)", 360),

    // Renders at roughly 720p vertically. This is FSR's own "Performance" preset
    // territory when the output is 1080p (1.5x per axis), and looks noticeably
    // sharper than 360p after RCAS sharpening.
    P720("720p (balanced)", 720);

    // Human-readable label shown in the YACL dropdown and the Video Settings button.
    public final String displayName;

    // The target internal vertical resolution in pixels; horizontal is derived
    // from the window's current aspect ratio at render time, not stored here.
    public final int verticalPixels;

    // Enum constructor — just stashes the two fields above.
    FsrRenderPreset(String displayName, int verticalPixels) {
        this.displayName = displayName;
        this.verticalPixels = verticalPixels;
    }

    // Computes the actual internal render width/height for a given output
    // (window) size, preserving the output's aspect ratio.
    public int[] resolveInternalResolution(int outputWidth, int outputHeight) {
        // Guards against a zero-height window during startup, which would
        // otherwise divide by zero below.
        if (outputHeight <= 0) {
            return new int[] { outputWidth, outputHeight };
        }
        // Aspect ratio of the real window (e.g. 16:9 = 1.777...).
        double aspect = (double) outputWidth / (double) outputHeight;
        // Internal height is just this preset's fixed vertical pixel count.
        int internalHeight = this.verticalPixels;
        // Internal width follows the same aspect ratio as the output.
        int internalWidth = (int) Math.round(internalHeight * aspect);
        // Vulkan swapchain-derived images generally want even dimensions;
        // round down to the nearest even number to avoid odd-size edge cases.
        internalWidth -= internalWidth % 2;
        internalHeight -= internalHeight % 2;
        return new int[] { internalWidth, internalHeight };
    }

    // Required by YACL's NameableEnum interface: returns the label shown in
    // the config screen's dropdown for this constant.
    @Override
    public Component getDisplayName() {
        return Component.literal(this.displayName);
    }
}
