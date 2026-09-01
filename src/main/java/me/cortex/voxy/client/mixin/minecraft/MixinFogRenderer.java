package me.cortex.voxy.client.mixin.minecraft;

import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.FogRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * In 1.20.1, FogRenderer.setupFog sets fog start/end via RenderSystem.setShaderFogStart/End.
 * We redirect those calls to push the fog far away when voxy rendering is active.
 */
@Mixin(value = FogRenderer.class)
public class MixinFogRenderer {
    @Inject(method = "setupFog", at = @At("TAIL"))
    private static void voxy$modifyFog(Camera camera, FogRenderer.FogMode fogMode, float farPlaneDistance, boolean thickFog, float partialTick, CallbackInfo ci) {
        if (!VoxyConfig.CONFIG.isRenderingEnabled()) return;

        var vrs = IGetVoxyRenderSystem.getNullable();
        if (vrs == null) return;

        // Push render distance fog far away so voxy's LODs are visible
        if (!thickFog || VoxyConfig.CONFIG.useEnvironmentalFog) {
            com.mojang.blaze3d.systems.RenderSystem.setShaderFogStart(999999999f);
            com.mojang.blaze3d.systems.RenderSystem.setShaderFogEnd(999999999f);
        }
    }
}
