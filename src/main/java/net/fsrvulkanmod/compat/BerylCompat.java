package net.fsrvulkanmod.compat;

import net.fabricmc.loader.api.FabricLoader;

// This class deliberately contains NO Beryl code, NO decompiled Beryl classes,
// and no reference to Beryl's internal API. It only asks Fabric Loader "is a
// mod with this id installed?", which is public information Fabric exposes
// for every mod on the classpath, Beryl included. This is how we satisfy
// "upscale Beryl by default" without touching Beryl's ARR-licensed source.
public final class BerylCompat {

    // Beryl's published Modrinth/CurseForge mod id, per its listing page.
    // If Beryl ever changes its mod id this constant is the only thing to update.
    private static final String BERYL_MOD_ID = "beryl";

    // Private constructor: this is a static-only utility class, never instantiated.
    private BerylCompat() {}

    // True if Beryl is present in the current Fabric mod set, regardless of
    // whether it's actually active/enabled in-game.
    public static boolean isBerylLoaded() {
        return FabricLoader.getInstance().isModLoaded(BERYL_MOD_ID);
    }
}
