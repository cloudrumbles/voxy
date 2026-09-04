package me.cortex.voxy.client.mixin.sodium;

import me.cortex.voxy.client.VoxyClient;
import me.cortex.voxy.client.VoxyClientSmoke;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.util.IrisUtil;
import me.jellysquid.mods.sodium.client.gl.device.CommandList;
import me.jellysquid.mods.sodium.client.gl.device.RenderDevice;
import me.jellysquid.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import me.jellysquid.mods.sodium.client.render.chunk.DefaultChunkRenderer;
import me.jellysquid.mods.sodium.client.render.chunk.ShaderChunkRenderer;
import me.jellysquid.mods.sodium.client.render.chunk.lists.ChunkRenderListIterable;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import me.jellysquid.mods.sodium.client.render.chunk.vertex.format.ChunkVertexType;
import me.jellysquid.mods.sodium.client.render.viewport.CameraTransform;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = DefaultChunkRenderer.class, remap = false)
public abstract class MixinDefaultChunkRenderer extends ShaderChunkRenderer {

    public MixinDefaultChunkRenderer(RenderDevice device, ChunkVertexType vertexType) {
        super(device, vertexType);
    }

    @Inject(method = "render", at = @At(value = "HEAD"), cancellable = true)
    private void voxy$replaceTerrainRender(ChunkRenderMatrices matrices, CommandList commandList,
                                           ChunkRenderListIterable renderLists, TerrainRenderPass renderPass,
                                           CameraTransform camera, CallbackInfo callbackInfo) {
        if (VoxyClient.disableSodiumChunkRender()) {
            super.begin(renderPass);
            this.voxy$render(matrices, renderPass, camera);
            super.end(renderPass);
            callbackInfo.cancel();
        }
    }

    @Inject(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lme/jellysquid/mods/sodium/client/render/chunk/ShaderChunkRenderer;end(Lme/jellysquid/mods/sodium/client/render/chunk/terrain/TerrainRenderPass;)V",
                    shift = At.Shift.BEFORE
            )
    )
    private void voxy$injectTerrainRender(ChunkRenderMatrices matrices, CommandList commandList,
                                          ChunkRenderListIterable renderLists, TerrainRenderPass renderPass,
                                          CameraTransform camera, CallbackInfo callbackInfo) {
        this.voxy$render(matrices, renderPass, camera);
    }

    @Unique
    private void voxy$render(ChunkRenderMatrices matrices, TerrainRenderPass renderPass,
                             CameraTransform camera) {
        if (renderPass != DefaultTerrainRenderPasses.CUTOUT) {
            return;
        }

        var renderer = ((IGetVoxyRenderSystem) Minecraft.getInstance().levelRenderer)
                .voxy$getRenderSystem();
        if (renderer == null) {
            return;
        }

        Viewport<?> viewport;
        if (IrisUtil.irisShaderPackEnabled()) {
            viewport = renderer.getViewport();
        } else {
            viewport = renderer.setupViewport(matrices, camera.x, camera.y, camera.z);
        }
        renderer.renderOpaque(viewport);
        VoxyClientSmoke.recordSuccessfulRenderPass();
    }
}
