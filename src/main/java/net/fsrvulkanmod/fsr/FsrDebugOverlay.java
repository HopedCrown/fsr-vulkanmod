package net.fsrvulkanmod.fsr;

import net.fsrvulkanmod.FsrVulkanModClient;
import net.fsrvulkanmod.config.FsrConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

// Draws a small text overlay in the top-left corner showing the current
// internal render resolution, the output resolution, and the measured frame
// time — this exists specifically so you can verify, on your actual
// i5-1035G1, whether a given preset is reaching your +30 FPS goal instead of
// guessing from this conversation alone.
public final class FsrDebugOverlay {

    // Rolling average frame time, updated once per rendered frame, in
    // milliseconds — smoothed so the number doesn't flicker unreadably.
    private static float smoothedFrameTimeMs = 16.6f;

    // Timestamp (nanoseconds) of the previous frame, used to compute delta time.
    private static long lastFrameNanos = System.nanoTime();

    private FsrDebugOverlay() {}

    // Called once per rendered frame, from the HUD render mixin/event hook
    // (not shown here — wire this into Fabric API's HudRenderCallback, which
    // is a stable, version-independent hook unlike VulkanMod's own internals,
    // so it needs no VERIFY caveat).
    public static void render(GuiGraphics graphics) {
        FsrConfig config = FsrVulkanModClient.config();
        // Respects the user's toggle; does nothing at all if they haven't
        // turned the overlay on in the config screen.
        if (config == null || !config.showDebugOverlay) {
            return;
        }

        updateFrameTime();

        Minecraft client = Minecraft.getInstance();
        int windowWidth = client.getWindow().getWidth();
        int windowHeight = client.getWindow().getHeight();

        // Recomputes the preset's internal resolution the same way the
        // actual render hook does, so the overlay always reflects reality
        // even if you change the preset mid-session.
        int[] internalRes = config.preset.resolveInternalResolution(windowWidth, windowHeight);

        // Approximate FPS from the smoothed frame time; avoids showing
        // "Infinity" on the very first frame by flooring frame time at 1ms.
        float fps = 1000f / Math.max(1f, smoothedFrameTimeMs);

        String line1 = String.format("FSR: %s", config.enabled ? "ON" : "OFF");
        String line2 = String.format("Internal: %dx%d -> %dx%d", internalRes[0], internalRes[1], windowWidth, windowHeight);
        String line3 = String.format("Frame time: %.1fms (%.0f fps)", smoothedFrameTimeMs, fps);

        int x = 6;
        int y = 6;
        int lineHeight = 10;
        int color = 0xFFFFFFFF;

        // drawString is Minecraft's standard HUD text draw call — stable
        // across mod renderers, including VulkanMod, since the GUI pass is
        // always drawn through vanilla's GuiGraphics abstraction.
        graphics.drawString(client.font, line1, x, y, color);
        graphics.drawString(client.font, line2, x, y + lineHeight, color);
        graphics.drawString(client.font, line3, x, y + lineHeight * 2, color);
    }

    // Updates the smoothed frame-time estimate using an exponential moving
    // average, which reacts to real changes within a few frames but doesn't
    // jump around on every single frame's noise.
    private static void updateFrameTime() {
        long now = System.nanoTime();
        float deltaMs = (now - lastFrameNanos) / 1_000_000f;
        lastFrameNanos = now;

        // 0.1 weight on the new sample = roughly a 10-frame smoothing window.
        smoothedFrameTimeMs = smoothedFrameTimeMs * 0.9f + deltaMs * 0.1f;
    }
}
