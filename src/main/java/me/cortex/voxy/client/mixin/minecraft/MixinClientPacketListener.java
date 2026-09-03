package me.cortex.voxy.client.mixin.minecraft;

import me.cortex.voxy.client.ClientSessionEvents;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public abstract class MixinClientPacketListener {
    @Shadow
    public abstract Connection getConnection();

    /**
     * ClientPacketListener.handleLogin first enters on Netty's IO thread and
     * reschedules itself onto Minecraft's render thread. Initializing at HEAD
     * therefore races Minecraft's own login setup and observes a null gameMode.
     * At TAIL the ClientLevel and player controller are ready. The transport
     * address is captured directly because command-line --server connections do
     * not necessarily create a ServerData entry in Minecraft.
     */
    @Inject(method = "handleLogin", at = @At("TAIL"))
    private void voxy$init(ClientboundLoginPacket packet, CallbackInfo callbackInfo) {
        if (!ClientSessionEvents.inSession) {
            Connection connection = this.getConnection();
            ClientSessionEvents.sessionStart(connection == null ? null : connection.getRemoteAddress());
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
