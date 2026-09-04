package me.cortex.voxy.client;

import me.cortex.voxy.client.config.ModMenuIntegration;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

/** Forge entry point for the 1.19.2 port. */
@Mod(VoxyMod.MOD_ID)
public final class VoxyMod {
    public static final String MOD_ID = "voxy";

    public VoxyMod() {
        VoxyCommon.initialize();
        DistExecutor.safeRunWhenOn(Dist.CLIENT, () -> ClientOnly::initialize);
    }

    private static final class ClientOnly {
        private static void initialize() {
            VoxyClientSmoke.register();
            ModMenuIntegration.register();
            ModLoadingContext.get().registerExtensionPoint(
                    ConfigScreenHandler.ConfigScreenFactory.class,
                    () -> new ConfigScreenHandler.ConfigScreenFactory(ModMenuIntegration::createConfigScreen)
            );

            IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
            modBus.addListener(ClientOnly::onClientSetup);
            MinecraftForge.EVENT_BUS.addListener(ClientOnly::onRegisterClientCommands);
        }

        private static void onClientSetup(FMLClientSetupEvent event) {
            event.enqueueWork(VoxyClient::onInitializeClient);
        }

        private static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
            if (VoxyCommon.isAvailable()) {
                event.getDispatcher().register(VoxyCommands.register());
            }
        }
    }
}
