package net.fsrvulkanmod.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.fsrvulkanmod.compat.BerylCompat;
import net.fsrvulkanmod.fsr.FsrRenderPreset;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

// Holds every value the YACL screen and the Video Settings button can change,
// and knows how to persist itself to config/fsr-vulkanmod.json.
public class FsrConfig {

    // Master on/off switch for the whole upscaler.
    public boolean enabled;

    // Which internal resolution preset (360p/720p) EASU renders from.
    public FsrRenderPreset preset = FsrRenderPreset.P720;

    // RCAS sharpening strength, 0.0 (no sharpening) to 1.0 (maximum). FSR's own
    // convention is that LOWER numeric "sharpness" values in the shader mean
    // MORE sharpening, so this field is the user-facing, intuitive version
    // (higher = sharper) and gets inverted right before it's passed to RCAS.
    public float sharpness = 0.6f;

    // Lets you disable just the RCAS sharpening pass (keep EASU only) to claw
    // back a bit of extra GPU time on very weak iGPUs like the 1035G1's UHD.
    public boolean rcasEnabled = true;

    // Shows a small on-screen overlay with internal resolution + frame time,
    // so you can actually measure whether a preset is hitting your FPS target
    // instead of guessing.
    public boolean showDebugOverlay = false;

    // Lazily computed once per launch: did the user ever touch 'enabled'
    // manually? If not, and Beryl is present, we turn the upscaler on for them
    // (see resolveDefaultEnabled below) the first time the config is created.
    public boolean userHasSetEnabledManually = false;

    // Returns the on-disk location of the config file, inside Fabric's
    // per-instance config directory.
    private static Path configPath() {
        // FabricLoader exposes the right config folder for the current instance.
        return FabricLoader.getInstance().getConfigDir().resolve("fsr-vulkanmod.json");
    }

    // Loads the config from disk, or creates sensible (Beryl-aware) defaults
    // if no config file exists yet.
    public static FsrConfig load() {
        Path path = configPath();
        // If we've never saved a config before, this is a fresh install.
        if (!Files.exists(path)) {
            FsrConfig fresh = new FsrConfig();
            // Per your request: default to upscaling ON when Beryl is detected.
            fresh.enabled = BerylCompat.isBerylLoaded();
            // Immediately persist these first-run defaults so the file exists
            // next launch and so the overlay/README reflect reality.
            fresh.save();
            return fresh;
        }
        // Gson handles the actual JSON (de)serialization; pretty-printing makes
        // the file readable/editable by hand if you want to tweak it directly.
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            FsrConfig loaded = gson.fromJson(reader, FsrConfig.class);
            // Gson can return null on an empty/corrupt file; fall back safely.
            return loaded != null ? loaded : new FsrConfig();
        } catch (IOException e) {
            // Config read failures shouldn't crash the game; just use defaults
            // for this session and let save() overwrite the bad file later.
            return new FsrConfig();
        }
    }

    // Writes the current state of this config object back to disk.
    public void save() {
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        try (Writer writer = Files.newBufferedWriter(configPath(), StandardCharsets.UTF_8)) {
            gson.toJson(this, writer);
        } catch (IOException e) {
            // Non-fatal: the in-memory settings still apply for this session
            // even if we couldn't persist them.
        }
    }

    // Converts the user-facing 'sharpness' slider (0=off, 1=max) into FSR's
    // native RCAS sharpness parameter, which is an attenuation in *stops* and
    // runs the opposite direction (0.0 = sharpest, larger = softer).
    public float toRcasNativeSharpness() {
        // Clamp defensively in case a hand-edited config file has a bad value.
        float clamped = Math.max(0f, Math.min(1f, this.sharpness));
        // Maps our 0..1 "more is sharper" range onto FSR's roughly 0..2 stop
        // range, inverted so that our 1.0 becomes RCAS's 0.0 (max sharpen).
        return (1f - clamped) * 2f;
    }
}
