package net.fsrvulkanmod.config;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

// Registered in fabric.mod.json under the "modmenu" entrypoint. This class
// is only ever loaded if Mod Menu is actually installed, because Fabric only
// resolves entrypoints for mods that are present — so this has no runtime
// dependency on Mod Menu when it's absent, matching "modCompileOnly" in
// build.gradle.
public class FsrModMenuIntegration implements ModMenuApi {

    // Mod Menu calls this to get a factory for "open this mod's config
    // screen" when the player clicks our entry in the Mods list.
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        // The lambda just forwards to the same YACL screen builder the
        // Video Settings button and keybind use, so there's only one real
        // config screen implementation in the whole mod.
        return FsrYaclScreen::create;
    }
}
