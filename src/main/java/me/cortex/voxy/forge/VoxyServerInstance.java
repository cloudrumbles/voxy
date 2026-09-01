package me.cortex.voxy.forge;

import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.common.StorageConfigUtil;
import me.cortex.voxy.common.config.ConfigBuildCtx;
import me.cortex.voxy.common.config.section.SectionStorage;
import me.cortex.voxy.common.config.section.SectionStorageConfig;
import me.cortex.voxy.commonImpl.ImportManager;
import me.cortex.voxy.commonImpl.VoxyInstance;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.nio.file.Path;

// Dedicated-server counterpart to VoxyClientInstance. Provides the same
// VoxyInstance contract (storage, services, ingest gating) without any
// reference to client-only classes (Sodium, RenderResourceReuse, etc.).
// Constructed by VoxyMod's ServerAboutToStartEvent listener when running on
// Dist.DEDICATED_SERVER. The integrated-server case is still served by
// VoxyClientInstance.
public class VoxyServerInstance extends VoxyInstance {
    private final SectionStorageConfig storageConfig;
    private final Path basePath;

    public VoxyServerInstance() {
        super();
        this.basePath = resolveBasePath();
        this.storageConfig = StorageConfigUtil.getCreateStorageConfig(
                Config.class,
                c -> c.version == 1 && c.sectionStorageConfig != null,
                () -> DEFAULT_STORAGE_CONFIG,
                this.basePath).sectionStorageConfig;
        this.updateDedicatedThreads();
    }

    @Override
    public void updateDedicatedThreads() {
        // No Sodium on a dedicated server, so the "share builder threads"
        // accounting in the client version doesn't apply. Just use the
        // configured count straight.
        this.setNumThreads(Math.max(1, VoxyConfig.CONFIG.serviceThreads));
    }

    @Override
    protected ImportManager createImportManager() {
        // Base ImportManager is concrete and self-sufficient; only the client
        // variant adds GUI hooks we don't need server-side.
        return new ImportManager();
    }

    @Override
    protected SectionStorage createStorage(WorldIdentifier identifier) {
        var ctx = new ConfigBuildCtx();
        ctx.setProperty(ConfigBuildCtx.BASE_SAVE_PATH, this.basePath.toString());
        ctx.setProperty(ConfigBuildCtx.WORLD_IDENTIFIER, identifier.getWorldId());
        ctx.pushPath(ConfigBuildCtx.DEFAULT_STORAGE_PATH);
        return this.storageConfig.build(ctx);
    }

    public Path getStorageBasePath() {
        return this.basePath;
    }

    @Override
    public boolean isIngestEnabled(WorldIdentifier worldId) {
        // Live-ingest fires from the Sodium render-section mixin, which never
        // loads on a dedicated server. The flag is still honoured for
        // forward-compat, but in practice only distant-gen runs server-side
        // and it has its own gate (VoxyConfig.distantGenEnabled).
        return VoxyConfig.CONFIG.ingestEnabled;
    }

    private static Path resolveBasePath() {
        // Server world dir is available once ServerAboutToStartEvent has
        // fired (Forge populates ServerLifecycleHooks then). Voxy lives in
        // <world>/voxy alongside vanilla's region/ etc.
        var server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            // Defensive — shouldn't happen given when we construct, but if
            // it does, fall back to CWD/.voxy so we don't NPE during init.
            return java.nio.file.Paths.get(".voxy").toAbsolutePath();
        }
        return server.getWorldPath(LevelResource.ROOT).resolve("voxy").toAbsolutePath();
    }

    private static class Config {
        public int version = 1;
        public SectionStorageConfig sectionStorageConfig;
    }

    private static final Config DEFAULT_STORAGE_CONFIG;
    static {
        var config = new Config();
        config.sectionStorageConfig = StorageConfigUtil.createDefaultSerializer();
        DEFAULT_STORAGE_CONFIG = config;
    }
}
