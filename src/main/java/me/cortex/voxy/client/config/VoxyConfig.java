package me.cortex.voxy.client.config;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.util.cpu.CpuLayout;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;

public class VoxyConfig {
    private static final Gson GSON = new GsonBuilder()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .setPrettyPrinting()
            .excludeFieldsWithModifiers(Modifier.PRIVATE)
            .create();

    public static VoxyConfig CONFIG = loadOrCreate();

    public boolean enabled = true;
    public boolean enableRendering = true;
    public boolean ingestEnabled = true;
    public float sectionRenderDistance = 16;
    public int serviceThreads = (int) Math.max(CpuLayout.getCoreCount()/1.5, 1);
    public float subDivisionSize = 64;
    public boolean useEnvironmentalFog = true;
    public boolean dontUseSodiumBuilderThreads = false;

    // Distant generation (Phase 1, single-player overworld).
    // Default: off — opt-in. distantGenRadius is INDEPENDENT of voxy
    // render radius and MC render distance. distantGenThreads mirrors
    // DH-au-naturel's MINIMAL_IMPACT preset (~10% cores, min 1).
    public boolean distantGenEnabled = false;
    public int distantGenRadius = 128;
    public int distantGenThreads = Math.max(1, (int) Math.round(CpuLayout.getCoreCount() * 0.1));
    // Diagnostic toggle — when true, emits per-chunk INFO logs at each phase
    // of distant-gen processing (consider-submit, dispatch, ticket-add,
    // future-completion, voxelise-start, voxelise-end, markCompleted). Very
    // verbose; intended to help diagnose where chunks are getting stuck.
    public boolean distantGenVerboseLogging = false;
    // Periodic INFO-level telemetry lines (~1 per 5s each) from the
    // distant-gen driver and the AsyncNodeManager worker. Pure observability:
    // counters and their deltas since the previous heartbeat. Off by default
    // because an idle session otherwise accumulates them indefinitely; the
    // underlying counters are always maintained, so enabling this mid-session
    // still reports correct deltas.
    public boolean heartbeatLogging = false;
    // Section LRU cache capacity (entries; each holds up to a 256 KiB voxel
    // array). 0 = automatic, tiered by JVM max heap. The old fixed 1024/2048
    // sizing was far too small for large LOD radii: a mesh-rebuild wave at
    // 256+ chunk radius requests more distinct sections than the cache holds,
    // so sections cycle load -> evict -> reload, each cycle paying a RocksDB
    // get + ZSTD decompress + 256 KiB deserialize.
    public int sectionCacheSize = 0;

    // When true, LOD cells that don't yet have their finest-detail mesh ready
    // are hidden (rendered as a gap) instead of being filled with the coarser
    // parent mesh. Aesthetic preference: trades visible progressive refinement
    // for visible holes during streaming. Default false preserves the original
    // coarse-then-refine behaviour.
    public boolean hideUnrefinedLodCells = false;

    private static VoxyConfig loadOrCreate() {
        // The file load is intentionally NOT gated on VoxyCommon.isAvailable():
        // VoxyConfig's clinit can run before MixinRenderSystem fires
        // VoxyClient.initVoxyClient() (the call that sets the instance factory),
        // and that race depends on mod-load order. The previous gate forced
        // enabled=false in that window, which silently overrode whatever the
        // user had saved to disk. FMLPaths.CONFIGDIR is populated by Forge
        // before any mod constructs, so the file load is safe at clinit time.
        var path = getConfigPath();
        if (Files.exists(path)) {
            try (FileReader reader = new FileReader(path.toFile())) {
                var conf = GSON.fromJson(reader, VoxyConfig.class);
                if (conf != null) {
                    conf.save();
                    return conf;
                } else {
                    Logger.error("Failed to load voxy config, resetting");
                }
            } catch (IOException e) {
                Logger.error("Could not parse config", e);
            }
        }
        Logger.info("Config doesnt exist, creating new");
        var config = new VoxyConfig();
        config.save();
        return config;
    }

    public void save() {
        try {
            Files.writeString(getConfigPath(), GSON.toJson(this));
        } catch (IOException e) {
            Logger.error("Failed to write config file", e);
        }
    }

    private static Path getConfigPath() {
        return FMLPaths.CONFIGDIR.get()
                .resolve("voxy-config.json");
    }

    public boolean isRenderingEnabled() {
        return VoxyCommon.isAvailable() && this.enabled && this.enableRendering;
    }

    // Whether the user has voxy rendering configured to be on. Reflects only
    // the saved config state, not whether voxy's factory is currently
    // available. Use this at iris shader-build time (program-set construction,
    // standard-macros, common-uniforms) so patches/uniforms are emitted
    // whenever the user wants voxy rendering, even if the voxy factory
    // hasn't yet been registered. Use isRenderingEnabled() for runtime
    // decisions that require voxy to be actually alive.
    public boolean isRenderingConfigured() {
        return this.enabled && this.enableRendering;
    }
}
