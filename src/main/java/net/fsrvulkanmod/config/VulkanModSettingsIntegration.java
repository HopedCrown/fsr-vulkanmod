package net.fsrvulkanmod.config;

import net.fsrvulkanmod.FsrVulkanModClient;
import net.fsrvulkanmod.fsr.FsrRenderPreset;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.vulkanmod.config.gui.ModSettingsEntry;
import net.vulkanmod.config.gui.ModSettingsRegistry;
import net.vulkanmod.config.option.CyclingOption;
import net.vulkanmod.config.option.Option;
import net.vulkanmod.config.option.Page;
import net.vulkanmod.config.option.RangeOption;
import net.vulkanmod.config.option.SwitchOption;

import java.util.List;

// Registers our settings directly into VulkanMod's own Video Settings screen
// through its real, public mod-settings API — CONFIRMED to exist (not
// guessed) by parsing the actual VulkanMod jar's classes:
//   - net.vulkanmod.config.gui.ModSettingsRegistry (public static final
//     INSTANCE field, public addModEntry(ModSettingsEntry) method)
//   - net.vulkanmod.config.gui.ModSettingsEntry, whose constructor is
//     confirmed (via bytecode) to be:
//     (FormattedText modName, Supplier<Identifier> iconSupplier,
//      Supplier<List<Page>> optionPageSupplier, Runnable onApply)
//   - net.vulkanmod.config.option.Page.of(name).block(title).add(option)...done()
//   - net.vulkanmod.config.option.{SwitchOption, RangeOption, CyclingOption}
// This replaces a much riskier earlier approach (injecting a button into
// VOptionScreen's layout via mixin) with VulkanMod's own intended extension
// point — this is almost certainly how VulkanMod expects other mods (shader
// packs, etc.) to add a Video Settings entry.
public final class VulkanModSettingsIntegration {

    private VulkanModSettingsIntegration() {}

    // Call once from FsrVulkanModClient#onInitializeClient().
    public static void register() {
        ModSettingsEntry entry = new ModSettingsEntry(
                Component.literal("FSR Upscaler"),
                // Icon supplier: VulkanMod calls this lazily and caches the
                // result (confirmed by getIcon()'s null-check-then-cache
                // bytecode), so a mod icon texture only needs to exist once
                // this is actually invoked — points at a 16x16/32x32 PNG you
                // add under assets/fsrvulkanmod/textures/.
                () -> Identifier.fromNamespaceAndPath("fsrvulkanmod", "icon.png"),
                // Pages supplier: also called lazily (confirmed by
                // initPages()'s bytecode caching the result into a 'pages'
                // field on first call), so it's safe to read FsrConfig here
                // every time without worrying about doing it too early.
                VulkanModSettingsIntegration::buildPages,
                // Runs when the player hits "Apply"/"Done" on VulkanMod's own
                // screen (confirmed: runOnApply() just invokes this Runnable).
                // Our individual options already write straight into the
                // shared FsrConfig instance as they change (see each
                // option's Consumer below), so all that's left to do here is
                // persist it to disk.
                () -> FsrVulkanModClient.config().save()
        );
        ModSettingsRegistry.INSTANCE.addModEntry(entry);
    }

    private static List<Page> buildPages() {
        FsrConfig config = FsrVulkanModClient.config();

        // NOTE: Option is NOT a generic class in the real jar (its fields
        // are plain Object/Supplier/Consumer, confirmed by the bytecode dump
        // — generics are a compile-time-only concept and VulkanMod's source
        // simply doesn't use them here), so these are raw Option references,
        // not Option<Boolean> etc.
        Option enabledOption = new SwitchOption(
                Component.literal("Enable FSR Upscaling"),
                // Consumer<Boolean>: VulkanMod calls this with the new value
                // when the player toggles the switch.
                value -> {
                    config.enabled = (Boolean) value;
                    config.userHasSetEnabledManually = true;
                },
                // Supplier<Boolean>: VulkanMod calls this to read the
                // current value when (re)building the widget.
                () -> config.enabled);

        Option rcasOption = new SwitchOption(
                Component.literal("Sharpening (RCAS)"),
                value -> config.rcasEnabled = (Boolean) value,
                () -> config.rcasEnabled);

        // CyclingOption cycles through a plain Object[] of values; we feed it
        // FsrRenderPreset's constants directly and use setTranslator (an
        // inherited Option method, confirmed on the base Option class) to
        // show each preset's human-readable displayName instead of its raw
        // Java enum constant name.
        Option presetOption = new CyclingOption(
                Component.literal("Internal Render Resolution"),
                FsrRenderPreset.values(),
                value -> config.preset = (FsrRenderPreset) value,
                () -> config.preset)
                .setTranslator(value -> Component.literal(((FsrRenderPreset) value).displayName));

        // RangeOption works in integers; our config's 'sharpness' field is a
        // 0.0-1.0 float, so this option operates on a 0-100 integer scale and
        // converts both directions at the Consumer/Supplier boundary.
        Option sharpnessOption = new RangeOption(
                Component.literal("Sharpness"),
                0, 100, 5,
                value -> config.sharpness = ((Integer) value) / 100f,
                () -> Math.round(config.sharpness * 100f));

        Option overlayOption = new SwitchOption(
                Component.literal("Show Debug Overlay"),
                value -> config.showDebugOverlay = (Boolean) value,
                () -> config.showDebugOverlay);

        Page page = Page.of("fsr_vulkanmod")
                .block("General")
                .add(enabledOption)
                .add(presetOption)
                .add(rcasOption)
                .add(sharpnessOption)
                .add(overlayOption)
                .done();

        return List.of(page);
    }
}
