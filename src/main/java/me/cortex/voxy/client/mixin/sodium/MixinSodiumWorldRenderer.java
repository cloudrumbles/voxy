package me.cortex.voxy.client.mixin.sodium;

import com.mojang.blaze3d.vertex.PoseStack;
import me.cortex.voxy.client.VoxyClientSmoke;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.util.IrisUtil;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.jellysquid.mods.sodium.client.gl.device.CommandList;
import me.jellysquid.mods.sodium.client.render.SodiumWorldRenderer;
import me.jellysquid.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
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

    /**
     * Embeddium's modernized 1.19.2 renderer receives the live PoseStack here,
     * rather than the ChunkRenderMatrices argument used by newer branches.
     * Capture the matrices at the end of the solid terrain pass and render the
     * Voxy far terrain into the same frame.
     */
    @Inject(method = "drawChunkLayer", at = @At("TAIL"), remap = false)
    private void voxy$injectRender(RenderType renderLayer, PoseStack matrixStack,
                                   double cameraX, double cameraY, double cameraZ,
                                   CallbackInfo callbackInfo) {
        this.voxy$render(ChunkRenderMatrices.from(matrixStack), renderLayer,
                cameraX, cameraY, cameraZ);
    }

    @Unique
    private void voxy$render(ChunkRenderMatrices matrices, RenderType renderLayer,
                             double cameraX, double cameraY, double cameraZ) {
        if (renderLayer != RenderType.solid()) {
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
            viewport = renderer.setupViewport(matrices, cameraX, cameraY, cameraZ);
        }
        renderer.renderOpaque(viewport);
        VoxyClientSmoke.recordSuccessfulRenderPass();
    }
}
