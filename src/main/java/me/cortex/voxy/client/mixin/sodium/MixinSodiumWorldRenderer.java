package me.cortex.voxy.client.mixin.sodium;

import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.util.IrisUtil;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.jellysquid.mods.sodium.client.gl.device.CommandList;
import me.jellysquid.mods.sodium.client.render.SodiumWorldRenderer;
import me.jellysquid.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import org.spongepowered.asm.mixin.Dynamic;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = SodiumWorldRenderer.class, remap = false)
public class MixinSodiumWorldRenderer {
    @Inject(method = "initRenderer", at = @At("TAIL"), remap = false)
    private void voxy$injectThreadUpdate(CommandList commandList, CallbackInfo callbackInfo) {
        var instance = VoxyCommon.getInstance();
        if (instance != null) {
            instance.updateDedicatedThreads();
        }
    }

    @Unique
    private ChunkRenderMatrices voxy$capturedMatrices;

    @Dynamic("Selected only when the installed SodiumWorldRenderer exposes ChunkRenderMatrices")
    @Inject(
            method = "drawChunkLayer(Lnet/minecraft/client/renderer/RenderType;Lme/jellysquid/mods/sodium/client/render/chunk/ChunkRenderMatrices;DDD)V",
            at = @At("HEAD"),
            remap = false)
    private void voxy$captureMatrices(
            RenderType renderLayer,
            ChunkRenderMatrices matrices,
            double x,
            double y,
            double z,
            CallbackInfo callbackInfo) {
        this.voxy$capturedMatrices = matrices;
    }

    @Dynamic("Selected only when the installed SodiumWorldRenderer exposes ChunkRenderMatrices")
    @Inject(
            method = "drawChunkLayer(Lnet/minecraft/client/renderer/RenderType;Lme/jellysquid/mods/sodium/client/render/chunk/ChunkRenderMatrices;DDD)V",
            at = @At("TAIL"),
            remap = false)
    private void voxy$injectRender(
            RenderType renderLayer,
            ChunkRenderMatrices matrices,
            double x,
            double y,
            double z,
            CallbackInfo callbackInfo) {
        this.voxy$render(this.voxy$capturedMatrices, renderLayer, x, y, z);
    }

    @Unique
    private void voxy$render(
            ChunkRenderMatrices matrices,
            RenderType renderLayer,
            double x,
            double y,
            double z) {
        if (renderLayer != RenderType.solid()) {
            return;
        }

        var renderer = ((IGetVoxyRenderSystem) Minecraft.getInstance().levelRenderer).voxy$getRenderSystem();
        if (renderer == null) {
            return;
        }

        Viewport<?> viewport;
        if (IrisUtil.irisShaderPackEnabled()) {
            viewport = renderer.getViewport();
        } else {
            viewport = renderer.setupViewport(matrices, x, y, z);
        }
        renderer.renderOpaque(viewport);
    }
}
