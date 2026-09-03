package me.cortex.voxy.client;

import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.common.config.section.SectionSerializationStorage;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.fml.ModList;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * Opt-in packaged-client validation probe.
 *
 * <p>The listener is not registered in normal installations because
 * {@code voxy.smoke.phase} is unset. CI uses three phases: title,
 * world-write, and world-reopen.</p>
 */
public final class VoxyClientSmoke {
    private static final String PHASE = System.getProperty("voxy.smoke.phase", "")
            .trim().toLowerCase(Locale.ROOT);
    private static final AtomicBoolean REGISTERED = new AtomicBoolean();
    private static final AtomicLong SUCCESSFUL_RENDER_PASSES = new AtomicLong();

    private static boolean completed;
    private static int ticks;
    private static int worldReadyTicks;
    private static long initialPersistenceBytes = -1L;

    private VoxyClientSmoke() {
    }

    public static void register() {
        if (!PHASE.isEmpty() && REGISTERED.compareAndSet(false, true)) {
            MinecraftForge.EVENT_BUS.addListener(VoxyClientSmoke::onClientTick);
        }
    }

    /**
     * Records completion of the injected Voxy terrain pass. This is a no-op in
     * ordinary installations because the smoke-test phase property is unset.
     */
    public static void recordSuccessfulRenderPass() {
        if (isWorldPhase()) {
            SUCCESSFUL_RENDER_PASSES.incrementAndGet();
        }
    }

    private static void onClientTick(TickEvent.ClientTickEvent event) {
        if (completed || event.phase != TickEvent.Phase.END) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        ticks++;

        if (initialPersistenceBytes < 0L) {
            initialPersistenceBytes = persistenceBytes(minecraft.gameDirectory.toPath());
        }

        if ("title".equals(PHASE)) {
            if (minecraft.screen instanceof TitleScreen
                    && ModList.get().isLoaded("voxy")
                    && ModList.get().isLoaded("embeddium")
                    && VoxyClient.isRenderBackendInitialized()
                    && VoxyClient.isRenderBackendReady()) {
                succeed("title screen reached with the packaged Voxy mod and render backend ready");
                return;
            }
        } else if (isWorldPhase()) {
            if (minecraft.level != null && minecraft.player != null) {
                worldReadyTicks++;
                boolean renderSystemCreated = minecraft.levelRenderer instanceof IGetVoxyRenderSystem rendererAccess
                        && rendererAccess.voxy$getRenderSystem() != null;
                boolean instanceCreated = VoxyCommon.getInstance() instanceof VoxyClientInstance;
                long successfulLoads = SectionSerializationStorage.getSuccessfulLoadCount();
                long successfulSaves = SectionSerializationStorage.getSuccessfulSaveCount();
                long successfulRenderPasses = SUCCESSFUL_RENDER_PASSES.get();
                long currentPersistenceBytes = persistenceBytes(minecraft.gameDirectory.toPath());

                boolean storageCondition;
                if ("world-write".equals(PHASE)) {
                    storageCondition = successfulSaves > 0L && currentPersistenceBytes > 0L;
                } else {
                    storageCondition = initialPersistenceBytes > 0L && successfulLoads > 0L;
                }

                if (worldReadyTicks >= 100
                        && renderSystemCreated
                        && instanceCreated
                        && successfulRenderPasses > 0L
                        && storageCondition) {
                    succeed("world joined with a live Voxy renderer, a completed terrain pass, and verified section "
                            + ("world-write".equals(PHASE) ? "persistence writes" : "persistence reads"));
                    return;
                }
            } else {
                worldReadyTicks = 0;
            }
        } else {
            fail("unknown smoke phase");
            return;
        }

        int timeoutTicks = isWorldPhase() ? 3600 : 1200;
        if (ticks >= timeoutTicks) {
            fail("timed out before the phase contract was satisfied");
        }
    }

    private static boolean isWorldPhase() {
        return "world-write".equals(PHASE) || "world-reopen".equals(PHASE);
    }

    private static void succeed(String detail) {
        finish(true, detail);
    }

    private static void fail(String detail) {
        finish(false, detail);
    }

    private static void finish(boolean success, String detail) {
        if (completed) {
            return;
        }
        completed = true;
        writeResult(success, detail);
        Minecraft.getInstance().stop();
    }

    private static void writeResult(boolean success, String detail) {
        Minecraft minecraft = Minecraft.getInstance();
        Path gameDirectory = minecraft.gameDirectory.toPath();
        boolean renderSystemCreated = minecraft.levelRenderer instanceof IGetVoxyRenderSystem rendererAccess
                && rendererAccess.voxy$getRenderSystem() != null;
        String storageBasePath = "";
        if (VoxyCommon.getInstance() instanceof VoxyClientInstance instance) {
            storageBasePath = instance.getStorageBasePath().toString();
        }

        String json = "{\n"
                + "  \"success\": " + success + ",\n"
                + "  \"phase\": \"" + escape(PHASE) + "\",\n"
                + "  \"detail\": \"" + escape(detail) + "\",\n"
                + "  \"ticks\": " + ticks + ",\n"
                + "  \"worldReadyTicks\": " + worldReadyTicks + ",\n"
                + "  \"voxyLoaded\": " + ModList.get().isLoaded("voxy") + ",\n"
                + "  \"embeddiumLoaded\": " + ModList.get().isLoaded("embeddium") + ",\n"
                + "  \"backendInitialized\": " + VoxyClient.isRenderBackendInitialized() + ",\n"
                + "  \"backendReady\": " + VoxyClient.isRenderBackendReady() + ",\n"
                + "  \"worldJoined\": " + (minecraft.level != null && minecraft.player != null) + ",\n"
                + "  \"instanceCreated\": " + (VoxyCommon.getInstance() instanceof VoxyClientInstance) + ",\n"
                + "  \"renderSystemCreated\": " + renderSystemCreated + ",\n"
                + "  \"successfulRenderPasses\": " + SUCCESSFUL_RENDER_PASSES.get() + ",\n"
                + "  \"sectionLoadAttempts\": " + SectionSerializationStorage.getLoadAttemptCount() + ",\n"
                + "  \"successfulSectionLoads\": " + SectionSerializationStorage.getSuccessfulLoadCount() + ",\n"
                + "  \"successfulSectionSaves\": " + SectionSerializationStorage.getSuccessfulSaveCount() + ",\n"
                + "  \"initialPersistenceBytes\": " + initialPersistenceBytes + ",\n"
                + "  \"currentPersistenceBytes\": " + persistenceBytes(gameDirectory) + ",\n"
                + "  \"storageBasePath\": \"" + escape(storageBasePath) + "\",\n"
                + "  \"timestamp\": \"" + Instant.now() + "\"\n"
                + "}\n";

        Path result = gameDirectory.resolve("voxy-smoke-" + PHASE + ".json");
        try {
            Files.createDirectories(gameDirectory);
            Files.writeString(result, json);
        } catch (IOException exception) {
            exception.printStackTrace();
            Runtime.getRuntime().halt(3);
        }
    }

    private static long persistenceBytes(Path gameDirectory) {
        Path root = gameDirectory.resolve(".voxy").resolve("saves");
        if (!Files.isDirectory(root)) {
            return 0L;
        }
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(Files::isRegularFile)
                    .mapToLong(path -> {
                        try {
                            return Files.size(path);
                        } catch (IOException ignored) {
                            return 0L;
                        }
                    })
                    .sum();
        } catch (IOException ignored) {
            return 0L;
        }
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
