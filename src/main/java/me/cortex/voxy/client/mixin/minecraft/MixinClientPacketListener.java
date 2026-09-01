package me.cortex.voxy.client.mixin.minecraft;

import me.cortex.voxy.client.VoxyClientInstance;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public class MixinClientPacketListener {
    // On 1.20.1 Forge, Minecraft.setLevel() (which fires LevelRenderer.allChanged
    // and thus voxy's renderer reload) happens INSIDE handleLogin, before its
    // TAIL. Injecting at HEAD ensures the voxy instance exists by the time
    // reloadVoxyRenderer runs, otherwise the renderer never attaches and the
    // user sees no voxy rendering ("Not creating renderer due to null instance").
    @Inject(method = "handleLogin", at = @At("HEAD"))
    private void voxy$init(ClientboundLoginPacket packet, CallbackInfo ci) {
        if (VoxyCommon.isAvailable() && !VoxyClientInstance.isInGame) {
            VoxyClientInstance.isInGame = true;
            if (VoxyConfig.CONFIG.enabled) {
                if (VoxyCommon.getInstance() != null) {
                    VoxyCommon.shutdownInstance();
                }
                VoxyCommon.createInstance();
            }
        }
    }
}
