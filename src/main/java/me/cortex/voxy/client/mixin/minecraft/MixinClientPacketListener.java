package me.cortex.voxy.client.mixin.minecraft;

import me.cortex.voxy.client.ClientSessionEvents;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public class MixinClientPacketListener {
    /**
     * ClientPacketListener.handleLogin first enters on Netty's IO thread and
     * reschedules itself onto Minecraft's render thread. Initializing at HEAD
     * therefore races Minecraft's own login setup and observes a null gameMode.
     * At TAIL the ClientLevel, player controller, and server identity are ready.
     */
    @Inject(method = "handleLogin", at = @At("TAIL"))
    private void voxy$init(ClientboundLoginPacket packet, CallbackInfo callbackInfo) {
        if (!ClientSessionEvents.inSession) {
            ClientSessionEvents.sessionStart();
        }

        // LevelRenderer may already have handled its first allChanged() call
        // before the Voxy instance existed. Complete that half of the lifecycle
        // explicitly so the first received chunks can be ingested and rendered.
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level != null && minecraft.levelRenderer instanceof IGetVoxyRenderSystem rendererAccess
                && rendererAccess.voxy$getRenderSystem() == null) {
            rendererAccess.voxy$createRenderer();
        }
    }
}
