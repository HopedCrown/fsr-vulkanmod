package net.fsrvulkanmod;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fsrvulkanmod.config.FsrConfig;
import net.fsrvulkanmod.config.FsrYaclScreen;
import net.fsrvulkanmod.fsr.FsrDebugOverlay;
import net.fsrvulkanmod.fsr.FsrUpscalePass;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

// Fabric calls onInitializeClient() once, during client startup, because
// this class is registered as a "client" entrypoint in fabric.mod.json.
public class FsrVulkanModClient implements ClientModInitializer {

    // One shared config instance for the whole session; loaded on startup and
    // written back to disk whenever the YACL screen saves.
    private static FsrConfig config;

    // One shared upscale pass instance, created once and reused every frame.
    private static FsrUpscalePass upscalePass;

    // Lets other classes (mixins, the video-settings button) reach the
    // current config without threading it through constructors everywhere.
    public static FsrConfig config() {
        return config;
    }

    // Lets the render-hook mixin reach the upscale pass to record its
    // per-frame compute dispatches.
    public static FsrUpscalePass upscalePass() {
        return upscalePass;
    }

    @Override
    public void onInitializeClient() {
        // Loads existing settings, or creates Beryl-aware defaults on first run.
        config = FsrConfig.load();

        // Registers our settings into VulkanMod's own Video Settings screen
        // via its real public API — see VulkanModSettingsIntegration for the
        // confirmed (not guessed) class/method names this relies on.
        net.fsrvulkanmod.config.VulkanModSettingsIntegration.register();

        // Builds the Vulkan pipelines/descriptor layouts. Must run after
        // VulkanMod has created its VkDevice — VERIFY this entrypoint actually
        // fires late enough; if VulkanMod initializes Vulkan lazily on first
        // frame rather than at mod-init time, move this call into the render
        // mixin's first invocation instead (guarded by an "already
        // initialized" flag) so vkDeviceHandle() doesn't run too early.
        upscalePass = new FsrUpscalePass();
        try {
            upscalePass.init();
        } catch (UnsupportedOperationException e) {
            // The integration seam in FsrUpscalePass isn't wired up yet (see
            // that class's VERIFY comments) — log instead of crashing the
            // game so the rest of the mod (config screen, Beryl detection)
            // still works while the Vulkan hookup is finished.
            System.err.println("[FSR VulkanMod] Upscale pass not yet wired to VulkanMod internals: " + e.getMessage());
        }

        // Registers a keybinding (default: unbound) that opens the config
        // screen directly, as an alternative to the Video Settings button.
        KeyMapping openConfigKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.fsrvulkanmod.open_config",
                GLFW.GLFW_KEY_UNKNOWN,
                "category.fsrvulkanmod"));

        // Fabric API's client tick event checks every tick whether the
        // keybind was just pressed, and if so opens the YACL screen.
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openConfigKey.consumeClick()) {
                client.setScreen(FsrYaclScreen.create(client.screen));
            }
        });

        // Draws the optional debug overlay (resolution + frame time) every
        // frame, right after vanilla's own HUD. This hook is Fabric API's
        // own, not VulkanMod's, so unlike the mixins above it needs no
        // VERIFY caveat — it's stable across renderers.
        HudRenderCallback.EVENT.register((graphics, tickDelta) -> FsrDebugOverlay.render(graphics));
    }

    // Convenience used by the Video Settings mixin to open the same screen
    // without duplicating the Minecraft#setScreen call site.
    public static void openConfigScreen() {
        Minecraft client = Minecraft.getInstance();
        client.setScreen(FsrYaclScreen.create(client.screen));
    }
}
