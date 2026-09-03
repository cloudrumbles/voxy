package me.cortex.voxy.client.mixin.sodium;

import me.cortex.voxy.client.ICheekyClientChunkCache;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.common.world.service.VoxelIngestService;
import me.cortex.voxy.commonImpl.ForgePlatform;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.jellysquid.mods.sodium.client.gl.device.CommandList;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSection;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSectionManager;
import me.jellysquid.mods.sodium.client.render.chunk.compile.executor.ChunkBuilder;
import me.jellysquid.mods.sodium.client.render.chunk.data.BuiltSectionInfo;
import me.jellysquid.mods.sodium.client.render.chunk.map.ChunkTrackerHolder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.ChunkStatus;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = RenderSectionManager.class, remap = false)
public class MixinRenderSectionManager {
    @Unique
    private static final boolean BOBBY_INSTALLED = ForgePlatform.isModLoaded("bobby");

    @Shadow
    @Final
    private ClientLevel world;

    @Shadow
    @Final
    private ChunkBuilder builder;

    @Unique
    private long cachedChunkPos = -1;

    @Unique
    private int cachedChunkStatus;

    @Unique
    private int bottomSectionY;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void voxy$resetChunkTracker(ClientLevel level, int renderDistance,
                                        CommandList commandList, CallbackInfo callbackInfo) {
        if (Minecraft.getInstance().levelRenderer != null) {
            VoxyRenderSystem system = ((IGetVoxyRenderSystem) Minecraft.getInstance().levelRenderer).voxy$getRenderSystem();
            if (system != null) {
                system.chunkBoundRenderer.reset();
            }
        }
        this.bottomSectionY = this.world.getMinBuildHeight() >> 4;
    }

    @Inject(method = "onChunkRemoved", at = @At("HEAD"))
    private void voxy$injectIngest(int x, int z, CallbackInfo callbackInfo) {
        if (!VoxyConfig.CONFIG.ingestEnabled || BOBBY_INSTALLED) {
            return;
        }

        ICheekyClientChunkCache cache = (ICheekyClientChunkCache) this.world.getChunkSource();
        var chunk = cache.voxy$cheekyGetChunk(x, z);
        if (chunk != null) {
            VoxelIngestService.tryAutoIngestChunk(chunk);
        }
    }

    @Inject(method = "onChunkAdded", at = @At("HEAD"))
    private void voxy$ingestOnAdd(int x, int z, CallbackInfo callbackInfo) {
        if (!VoxyConfig.CONFIG.ingestEnabled) {
            return;
        }

        var chunk = this.world.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
        if (chunk != null) {
            VoxelIngestService.tryAutoIngestChunk(chunk);
        }
    }

    @Redirect(
            method = "updateSectionInfo",
            at = @At(
                    value = "INVOKE",
                    target = "Lme/jellysquid/mods/sodium/client/render/chunk/RenderSection;setInfo(Lme/jellysquid/mods/sodium/client/render/chunk/data/BuiltSectionInfo;)V"
            )
    )
    private void voxy$updateOnUpload(RenderSection section, BuiltSectionInfo info) {
        boolean wasBuilt = section.getFlags() != 0;
        int combinedFlags = section.getFlags();
        section.setInfo(info);
        boolean isBuilt = section.getFlags() != 0;

        if (wasBuilt == isBuilt) {
            return;
        }

        combinedFlags |= section.getFlags();
        if (combinedFlags == 0 || Minecraft.getInstance().levelRenderer == null) {
            return;
        }

        VoxyRenderSystem system = ((IGetVoxyRenderSystem) Minecraft.getInstance().levelRenderer).voxy$getRenderSystem();
        if (system == null) {
            return;
        }

        int x = section.getChunkX();
        int y = section.getChunkY();
        int z = section.getChunkZ();

        if (wasBuilt && VoxyConfig.CONFIG.ingestEnabled) {
            this.voxy$ingestSectionIfReady(system, x, y, z);
        }

        if (VoxyCommon.IS_MINE_IN_ABYSS) {
            int sector = (x + 512) >> 10;
            x -= sector << 10;
            y += 16 + (256 - 32 - sector * 30);
        }

        long position = SectionPos.asLong(x, y, z);
        if (wasBuilt) {
            system.chunkBoundRenderer.removeSection(position);
        } else {
            system.chunkBoundRenderer.addSection(position);
            if (VoxyConfig.CONFIG.ingestEnabled) {
                this.voxy$ingestSectionIfReady(system, x, y, z);
            }
        }
    }

    @Unique
    private void voxy$ingestSectionIfReady(VoxyRenderSystem system, int x, int y, int z) {
        var tracker = ((AccessorChunkTracker) ChunkTrackerHolder.get(this.world)).getChunkStatus();
        long key = ChunkPos.asLong(x, z);
        if (key != this.cachedChunkPos) {
            this.cachedChunkPos = key;
            this.cachedChunkStatus = tracker.getOrDefault(key, 0);
        }
        if (this.cachedChunkStatus != 3) {
            return;
        }

        var section = this.world.getChunk(x, z).getSection(y - this.bottomSectionY);
        var lightEngine = this.world.getLightEngine();
        SectionPos sectionPosition = SectionPos.of(x, y, z);
        var blockLight = lightEngine.getLayerListener(LightLayer.BLOCK).getDataLayerData(sectionPosition);
        var skyLight = lightEngine.getLayerListener(LightLayer.SKY).getDataLayerData(sectionPosition);
        VoxelIngestService.rawIngest(
                system.getEngine(), section, x, y, z,
                blockLight == null ? null : blockLight.copy(),
                skyLight == null ? null : skyLight.copy()
        );
    }
}
