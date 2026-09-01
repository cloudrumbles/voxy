package me.cortex.voxy.forge;

import com.mojang.brigadier.Command;
import me.cortex.voxy.client.DebugEntries;
import me.cortex.voxy.client.VoxyCommands;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.model.berbake.BerBakeProbe;
import me.cortex.voxy.client.config.VoxyConfigMenu;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.distantgen.VoxyDistantGen;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.CustomizeGuiOverlayEvent;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.event.server.ServerAboutToStartEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.server.ServerLifecycleHooks;

@Mod("voxy")
public class VoxyMod {
    public VoxyMod() {
        VoxyCommon.init();

        if (FMLEnvironment.dist == Dist.CLIENT) {
            MinecraftForge.EVENT_BUS.addListener(this::onRegisterClientCommands);
            MinecraftForge.EVENT_BUS.addListener(this::onDebugText);
            VoxyConfigMenu.register();
        }

        // Dedicated-server-only: register a server-side instance factory.
        // On client/integrated-server, VoxyClient.initVoxyClient (called from
        // MixinRenderSystem) sets the client factory. On a true dedicated
        // server those client mixins never load, so we have to bootstrap the
        // factory + instance here from server lifecycle events. The integrated
        // server in SP still uses VoxyClientInstance and ignores this branch.
        if (FMLEnvironment.dist == Dist.DEDICATED_SERVER) {
            MinecraftForge.EVENT_BUS.addListener(this::onServerAboutToStart);
        }

        // Register the lifecycle handlers via @SubscribeEvent. The previous
        // addListener(this::onLevelLoad) pattern silently failed to register
        // on this Forge build (most likely because the LevelEvent.Load inner
        // class type couldn't be inferred from the method-ref). register(this)
        // walks @SubscribeEvent annotations and uses the parameter type
        // directly, which is robust.
        MinecraftForge.EVENT_BUS.register(this);
    }

    private void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        if (VoxyCommon.isAvailable()) {
            event.getDispatcher().register(VoxyCommands.register());
            // Read-only BER-bake diagnostic; writes voxy-berprobe.csv to the game dir.
            event.getDispatcher().register(
                net.minecraft.commands.Commands.literal("voxyprobe").executes(ctx -> {
                    String result = BerBakeProbe.run();
                    ctx.getSource().sendSuccess(() -> net.minecraft.network.chat.Component.literal(result), false);
                    return Command.SINGLE_SUCCESS;
                })
            );
        }
    }

    // Append voxy's F3 lines. Upstream voxy registered these via Fabric's
    // debug-screen API / a MixinDebugScreenEntryList which doesn't exist in
    // 1.20.1. Forge 1.20.1 fires CustomizeGuiOverlayEvent.DebugText every
    // frame (not just when F3 is up), and only populates the vanilla left/
    // right lists when options.renderDebug is true. Guard on that so voxy
    // lines don't hang around on the regular HUD when F3 is closed.
    private void onDebugText(CustomizeGuiOverlayEvent.DebugText event) {
        var mc = net.minecraft.client.Minecraft.getInstance();
        if (mc == null || mc.options == null || !mc.options.renderDebug) {
            return;
        }
        event.getRight().addAll(DebugEntries.getDebugLines());
    }

    // Primary auto-create path: when a ServerLevel loads, attempt to create
    // a coordinator for it. May fail silently when distant-gen is enabled
    // but VoxyCommon.getInstance() is still null (LevelEvent.Load fires
    // during server startup; VoxyClientInstance gets created later when the
    // client connects via Netty). The tick handler below recovers from
    // that. Now multi-dimension: each ServerLevel (overworld, nether, end,
    // custom dims) gets its own coordinator.
    @SubscribeEvent
    public void onLevelLoad(LevelEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel serverLevel)) return;
        if (!VoxyConfig.CONFIG.distantGenEnabled) return;
        tryStartCoordinator(serverLevel);
    }

    // Fallback / catch-up: every server tick, ensure a coordinator exists
    // for every loaded ServerLevel if distant-gen is enabled. Fast path
    // (already exists) is a single map lookup per level. First time after
    // VoxyInstance becomes available this is what actually creates the
    // coordinators if onLevelLoad ran too early. Iterates all loaded levels
    // so newly-loaded dimensions (e.g. player enters Nether for the first
    // time after world join) get covered without needing another event.
    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        if (!VoxyConfig.CONFIG.distantGenEnabled) return;
        var server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        for (var level : server.getAllLevels()) {
            if (VoxyDistantGen.get(level) == null) {
                tryStartCoordinator(level);
            }
        }
    }

    private static void tryStartCoordinator(ServerLevel serverLevel) {
        var instance = VoxyCommon.getInstance();
        if (instance == null) return;
        try {
            var engine = WorldIdentifier.ofEngine(serverLevel);
            if (engine == null) return;
            // computeIfAbsent inside getOrCreate gives us atomic dedup.
            boolean isNew = VoxyDistantGen.get(serverLevel) == null;
            VoxyDistantGen.getOrCreate(serverLevel, engine, instance.getServiceManager());
            if (isNew) {
                Logger.info("Distant-gen started for " + serverLevel.dimension().location());
            }
        } catch (Throwable t) {
            Logger.error("Failed to start distant-gen for " + serverLevel.dimension().location() + ": " + t.getMessage(), t);
        }
    }

    @SubscribeEvent
    public void onLevelUnload(LevelEvent.Unload event) {
        if (!(event.getLevel() instanceof ServerLevel serverLevel)) return;
        VoxyDistantGen.shutdownFor(serverLevel);
    }

    // ServerStoppingEvent fires before any LevelEvent.Unload. Setting the
    // flag here gates the walker and scheduler off immediately so the
    // remaining shutdown ticks have minimal queued chunk-system work.
    // Mitigates the c2me mid-tick race against vanilla's entity-section
    // unload (crash 2026-05-24_09.26.21-server.txt).
    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        VoxyDistantGen.serverStopping = true;
    }

    // Reset the stopping flag at server start. The flag is static (process-
    // global) so SP world-rejoin after a previous shutdown would otherwise
    // see it true.
    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        VoxyDistantGen.serverStopping = false;
    }

    @SubscribeEvent
    public void onServerStopped(ServerStoppedEvent event) {
        VoxyDistantGen.shutdownAll();
    }

    // Dedicated-server bootstrap: ServerLifecycleHooks.getCurrentServer() is
    // populated by the time this fires, so VoxyServerInstance's basePath
    // resolution finds the server world dir. The factory is set once per
    // server start; the instance is created immediately so the rest of
    // voxy (storage, services, lifecycle handlers) operates as if voxy had
    // always been there.
    private void onServerAboutToStart(ServerAboutToStartEvent event) {
        if (VoxyCommon.isAvailable()) {
            // Idempotent on rejoin — factory already set, instance already
            // exists from a previous server cycle in this process.
            return;
        }
        try {
            VoxyCommon.setInstanceFactory(VoxyServerInstance::new);
            VoxyCommon.createInstance();
            Logger.info("Voxy server-side instance created");
        } catch (Throwable t) {
            Logger.error("Failed to bootstrap voxy server-side instance: " + t.getMessage(), t);
        }
    }
}
