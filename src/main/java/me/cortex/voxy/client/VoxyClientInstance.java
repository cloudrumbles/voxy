package me.cortex.voxy.client;

import me.cortex.voxy.client.compat.FlashbackCompat;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.RenderResourceReuse;
import me.cortex.voxy.client.mixin.sodium.AccessorSodiumWorldRenderer;
import me.cortex.voxy.common.StorageConfigUtil;
import me.cortex.voxy.common.config.ConfigBuildCtx;
import me.cortex.voxy.common.config.section.SectionStorage;
import me.cortex.voxy.common.config.section.SectionStorageConfig;
import me.cortex.voxy.commonImpl.ImportManager;
import me.cortex.voxy.commonImpl.VoxyInstance;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import me.jellysquid.mods.sodium.client.render.SodiumWorldRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Path;

public class VoxyClientInstance extends VoxyInstance {
    private final Config config;
    private final Path basePath;
    private final boolean noIngestOverride;

    public VoxyClientInstance() {
        super();
        var path = FlashbackCompat.getReplayStoragePath();
        this.noIngestOverride = path != null;
        if (path == null) {
            path = getBasePath();
        }
        this.basePath = path.normalize();
        this.config = StorageConfigUtil.getCreateStorageConfig(
                Config.class,
                config -> config.version == 1 && config.sectionStorageConfig != null,
                () -> DEFAULT_STORAGE_CONFIG,
                this.basePath);
        this.updateDedicatedThreads();
    }

    @Override
    public void updateDedicatedThreads() {
        int target = VoxyConfig.CONFIG.serviceThreads;
        if (!VoxyConfig.CONFIG.dontUseSodiumBuilderThreads) {
            var sodiumWorldRenderer = SodiumWorldRenderer.instanceNullable();
            if (sodiumWorldRenderer != null) {
                var renderSectionManager = ((AccessorSodiumWorldRenderer) sodiumWorldRenderer).getRenderSectionManager();
                if (renderSectionManager != null) {
                    this.setNumThreads(Math.max(1, target - renderSectionManager.getBuilder().getTotalThreadCount()));
                    return;
                }
            }
        }
        this.setNumThreads(target);
    }

    @Override
    protected ImportManager createImportManager() {
        return new ClientImportManager();
    }

    @Override
    protected SectionStorage createStorage(WorldIdentifier identifier) {
        var context = new ConfigBuildCtx();
        context.setProperty(ConfigBuildCtx.BASE_SAVE_PATH, this.basePath.toString());
        context.setProperty(ConfigBuildCtx.WORLD_IDENTIFIER, identifier.getWorldId());
        context.setProperty(
                ConfigBuildCtx.PLAYER_UUID,
                Minecraft.getInstance().getUser().getProfileId().toString().replace(':', '-'));
        context.pushPath(ConfigBuildCtx.DEFAULT_STORAGE_PATH);
        return this.config.sectionStorageConfig.build(context);
    }

    public Path getStorageBasePath() {
        return this.basePath;
    }

    @Override
    public boolean isIngestEnabled(WorldIdentifier worldId) {
        return !this.noIngestOverride && VoxyConfig.CONFIG.ingestEnabled;
    }

    @Override
    public void shutdown() {
        super.shutdown();
        RenderResourceReuse.clearResources();
    }

    private static class Config {
        public int version = 1;
        public boolean disabled = false;
        public SectionStorageConfig sectionStorageConfig;
    }

    private static final Config DEFAULT_STORAGE_CONFIG;

    static {
        var config = new Config();
        config.sectionStorageConfig = StorageConfigUtil.createDefaultSerializer();
        DEFAULT_STORAGE_CONFIG = config;
    }

    private static Path getBasePath() {
        Minecraft minecraft = Minecraft.getInstance();
        Path multiplayerRoot = minecraft.gameDirectory.toPath().resolve(".voxy").resolve("saves");
        var integratedServer = minecraft.getSingleplayerServer();
        if (integratedServer != null) {
            return integratedServer.getWorldPath(LevelResource.ROOT).resolve("voxy").toAbsolutePath();
        }

        if (minecraft.isConnectedToRealms()) {
            return multiplayerRoot.resolve("realms").toAbsolutePath();
        }

        String serverStorageKey = null;
        var serverData = minecraft.getCurrentServer();
        if (serverData != null) {
            serverStorageKey = ServerStorageKey.fromConfiguredAddress(serverData.ip);
        }
        if (serverStorageKey == null) {
            serverStorageKey = ClientSessionEvents.getServerStorageKey();
        }
        if (serverStorageKey == null) {
            throw new IllegalStateException(
                    "Cannot create Voxy persistence without a multiplayer server identity");
        }

        return multiplayerRoot.resolve(serverStorageKey).toAbsolutePath();
    }
}
