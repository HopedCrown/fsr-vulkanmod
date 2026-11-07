package net.fsrvulkanmod.config;

import dev.isxander.yacl3.api.ConfigCategory;
import dev.isxander.yacl3.api.NameableEnum;
import dev.isxander.yacl3.api.Option;
import dev.isxander.yacl3.api.OptionDescription;
import dev.isxander.yacl3.api.OptionGroup;
import dev.isxander.yacl3.api.YetAnotherConfigLib;
import dev.isxander.yacl3.api.controller.BooleanControllerBuilder;
import dev.isxander.yacl3.api.controller.EnumControllerBuilder;
import dev.isxander.yacl3.api.controller.FloatSliderControllerBuilder;
import net.fsrvulkanmod.fsr.FsrRenderPreset;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

// Builds the YACL screen, reachable via the mod's keybind and (if built) Mod
// Menu. This is now a SECOND way to reach the same settings — VulkanMod's own
// Video Settings screen gets its own native entry through the real
// ModSettingsRegistry API (see VulkanModSettingsIntegration), which doesn't
// use YACL at all. Both read/write the same FsrConfig instance, so changing
// a value in one place is reflected in the other the next time either is
// opened. YACL screens are rebuilt fresh each time they're opened, so this
// is a static factory method rather than a persistent object.
public class FsrYaclScreen {

    // Entry point: call this to get a Screen you can pass to
    // Minecraft#setScreen(...). 'parent' is the screen to return to on close
    // (usually the Video Settings screen itself).
    public static Screen create(Screen parent) {
        // Load the current on-disk config so the screen reflects real values,
        // and so we have something to mutate as the user drags sliders.
        FsrConfig config = FsrConfig.load();

        // YACL's builder pattern: start a new config GUI definition.
        YetAnotherConfigLib yacl = YetAnotherConfigLib.createBuilder()
                // Title shown at the top of the screen.
                .title(Component.literal("FSR Upscaler"))
                // A single category is enough for a mod this small.
                .category(buildMainCategory(config))
                // Called by YACL whenever the user hits "Save" / closes with changes.
                .save(config::save)
                .build();

        // Converts the YACL definition into an actual Minecraft Screen,
        // wired to return to 'parent' when the player backs out.
        return yacl.generateScreen(parent);
    }

    // Builds the one category holding every toggle/slider.
    private static ConfigCategory buildMainCategory(FsrConfig config) {
        return ConfigCategory.createBuilder()
                .name(Component.literal("General"))
                // The master enable/disable toggle, first so it's the most visible.
                .option(buildEnabledOption(config))
                // Groups the rest so the enable toggle visually stands apart.
                .group(buildRenderGroup(config))
                .group(buildDebugGroup(config))
                .build();
    }

    // The on/off switch for the whole upscaler.
    private static Option<Boolean> buildEnabledOption(FsrConfig config) {
        return Option.<Boolean>createBuilder()
                .name(Component.literal("Enable FSR Upscaling"))
                .description(OptionDescription.of(Component.literal(
                        "Renders the world at a lower internal resolution and upscales it with AMD FSR 1.0.")))
                // Binding ties this control directly to the config field: getter,
                // setter, and the value to reset to if the user hits "Reset".
                .binding(
                        // Default value if reset.
                        false,
                        // Getter: what the control currently shows.
                        () -> config.enabled,
                        // Setter: runs on every change, including marking that the
                        // user has now made an explicit choice (so we never silently
                        // re-enable this on a later launch based on Beryl detection).
                        value -> {
                            config.enabled = value;
                            config.userHasSetEnabledManually = true;
                        })
                .controller(BooleanControllerBuilder::create)
                .build();
    }

    // Render-resolution preset and sharpening controls.
    private static OptionGroup buildRenderGroup(FsrConfig config) {
        // The preset dropdown (360p vs 720p).
        Option<FsrRenderPreset> presetOption = Option.<FsrRenderPreset>createBuilder()
                .name(Component.literal("Internal Render Resolution"))
                .description(OptionDescription.of(Component.literal(
                        "Lower resolutions render faster but look softer after upscaling.")))
                .binding(FsrRenderPreset.P720, () -> config.preset, value -> config.preset = value)
                // EnumControllerBuilder reads FsrRenderPreset's displayName via
                // NameableEnum below for its on-screen label.
                .controller(EnumControllerBuilder::create)
                .build();

        // Whether RCAS sharpening runs at all.
        Option<Boolean> rcasToggle = Option.<Boolean>createBuilder()
                .name(Component.literal("Sharpening (RCAS)"))
                .description(OptionDescription.of(Component.literal(
                        "Disable to save a little extra GPU time on very weak hardware.")))
                .binding(true, () -> config.rcasEnabled, value -> config.rcasEnabled = value)
                .controller(BooleanControllerBuilder::create)
                .build();

        // Sharpening strength slider, 0 to 1 in 0.05 steps.
        Option<Float> sharpnessSlider = Option.<Float>createBuilder()
                .name(Component.literal("Sharpness"))
                .binding(0.6f, () -> config.sharpness, value -> config.sharpness = value)
                .controller(opt -> FloatSliderControllerBuilder.create(opt)
                        .range(0f, 1f)
                        .step(0.05f))
                .build();

        return OptionGroup.createBuilder()
                .name(Component.literal("Rendering"))
                .option(presetOption)
                .option(rcasToggle)
                .option(sharpnessSlider)
                .build();
    }

    // Debug/diagnostic controls.
    private static OptionGroup buildDebugGroup(FsrConfig config) {
        Option<Boolean> overlayToggle = Option.<Boolean>createBuilder()
                .name(Component.literal("Show Debug Overlay"))
                .description(OptionDescription.of(Component.literal(
                        "Displays internal resolution and frame time so you can measure the FPS gain.")))
                .binding(false, () -> config.showDebugOverlay, value -> config.showDebugOverlay = value)
                .controller(BooleanControllerBuilder::create)
                .build();

        return OptionGroup.createBuilder()
                .name(Component.literal("Debug"))
                .option(overlayToggle)
                .build();
    }
}
