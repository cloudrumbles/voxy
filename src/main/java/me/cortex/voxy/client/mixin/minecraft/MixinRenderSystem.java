package me.cortex.voxy.client.mixin.minecraft;

import com.mojang.blaze3d.systems.RenderSystem;
import me.cortex.voxy.client.VoxyClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(RenderSystem.class)
public class MixinRenderSystem {
    @Inject(method = "initRenderer", remap = false, at = @At("RETURN"))
    private static void voxy$injectInit(int debugVerbosity, boolean sync, CallbackInfo callbackInfo) {
        VoxyClient.initVoxyClient();
    }
}
