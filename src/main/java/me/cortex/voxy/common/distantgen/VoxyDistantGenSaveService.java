package me.cortex.voxy.common.distantgen;

import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.thread.Service;
import me.cortex.voxy.common.thread.ServiceManager;
import me.cortex.voxy.common.voxelization.ILightingSupplier;
import me.cortex.voxy.common.voxelization.VoxelizedSection;
import me.cortex.voxy.common.voxelization.WorldConversionFactory;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldUpdater;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.lighting.LayerLightSectionStorage;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.function.LongConsumer;

// Consumes generated LevelChunks delivered via VoxyDistantGenTicketManager's
// future callback. Voxelises each section through WorldConversionFactory.convert
// (which applies VoxyStateProxyRegistry) and feeds WorldUpdater.insertUpdate.
// Mirrors VoxelIngestService's per-section topology but is gated separately
// (does not consult isIngestEnabled) so distant-gen can run while live-ingest
// is off.
public class VoxyDistantGenSaveService {
    private static final ThreadLocal<VoxelizedSection> SECTION_CACHE = ThreadLocal.withInitial(VoxelizedSection::createEmpty);

    private record SaveTask(WorldEngine engine, LevelChunk chunk, long chunkPosKey, LongConsumer onDone) {}

    private final Service service;
    private final ConcurrentLinkedDeque<SaveTask> queue = new ConcurrentLinkedDeque<>();

    // Diagnostic counters.
    public final java.util.concurrent.atomic.AtomicLong cntVoxeliseSucceeded = new java.util.concurrent.atomic.AtomicLong();
    public final java.util.concurrent.atomic.AtomicLong cntVoxeliseFailed = new java.util.concurrent.atomic.AtomicLong();
    public final java.util.concurrent.atomic.AtomicLong cntSectionsProcessed = new java.util.concurrent.atomic.AtomicLong();
    public final java.util.concurrent.atomic.AtomicLong cntSectionsSkipped = new java.util.concurrent.atomic.AtomicLong();

    public VoxyDistantGenSaveService(ServiceManager pool) {
        this.service = pool.createServiceNoCleanup(() -> this::processJob, 4000, "Distant-gen save");
    }

    // Enqueue a generated chunk for voxelisation. onDone fires with the chunk key
    // after voxelisation completes (or fails) so the ticket manager can release
    // the ticket and decrement its in-flight counter.
    public void enqueue(WorldEngine engine, LevelChunk chunk, long chunkPosKey, LongConsumer onDone) {
        if (!this.service.isLive()) {
            onDone.accept(chunkPosKey);
            return;
        }
        engine.markActive();
        this.queue.add(new SaveTask(engine, chunk, chunkPosKey, onDone));
        try {
            this.service.execute();
        } catch (Exception e) {
            Logger.error("Distant-gen save service execute failed; assume shutting down", e);
            onDone.accept(chunkPosKey);
        }
    }

    private void processJob() {
        var task = this.queue.pop();
        boolean verbose = me.cortex.voxy.client.config.VoxyConfig.CONFIG.distantGenVerboseLogging;
        if (verbose) Logger.info("Distant-gen save-svc: voxelise START (" + task.chunk.getPos().x + "," + task.chunk.getPos().z + ")");
        try {
            int[] sectionStats = voxeliseChunk(task.engine, task.chunk);
            this.cntVoxeliseSucceeded.incrementAndGet();
            this.cntSectionsProcessed.addAndGet(sectionStats[0]);
            this.cntSectionsSkipped.addAndGet(sectionStats[1]);
            if (verbose) Logger.info("Distant-gen save-svc: voxelise OK (" + task.chunk.getPos().x + "," + task.chunk.getPos().z + ") processed=" + sectionStats[0] + " skipped=" + sectionStats[1]);
        } catch (Throwable t) {
            this.cntVoxeliseFailed.incrementAndGet();
            Logger.error("Distant-gen voxelise failed at " + task.chunk.getPos(), t);
        } finally {
            task.onDone.accept(task.chunkPosKey);
        }
    }

    // Returns int[2] = {sectionsProcessed, sectionsSkipped} so callers can
    // attribute stats without parsing log lines.
    private static int[] voxeliseChunk(WorldEngine engine, LevelChunk chunk) {
        int processed = 0;
        int skipped = 0;
        if (!engine.isLive()) {
            Logger.info("Distant-gen save-svc: voxelise ABORTED — engine not live at " + chunk.getPos());
            return new int[] {0, 0};
        }
        engine.markActive();

        var lightProvider = chunk.getLevel().getLightEngine();
        var blockLayer = lightProvider.getLayerListener(LightLayer.BLOCK);
        var skyLayer = lightProvider.getLayerListener(LightLayer.SKY);
        var mapper = engine.getMapper();

        int cx = chunk.getPos().x;
        int cz = chunk.getPos().z;

        int sectionY = chunk.getMinSection() - 1;
        for (var section : chunk.getSections()) {
            sectionY++;
            if (section == null) { skipped++; continue; }

            var sectionPos = SectionPos.of(chunk.getPos(), sectionY);

            // Skip purely-air sections that also lack any lighting data.
            // Mirrors VoxelIngestService's behaviour to avoid asymmetric LOD
            // population between live-ingest and distant-gen paths. The cost
            // of NOT skipping is significant (~3x section writes per chunk,
            // mostly throwaway zero-into-zero work) for an optimisation that
            // appears to be load-bearing — the renderer treats unwritten
            // worldSection quadrants as air, so processing them adds no
            // visible data.
            boolean hasBlockLight = lightProvider.getDebugSectionType(LightLayer.BLOCK, sectionPos)
                    == LayerLightSectionStorage.SectionType.LIGHT_AND_DATA;
            boolean hasSkyLight = lightProvider.getDebugSectionType(LightLayer.SKY, sectionPos)
                    == LayerLightSectionStorage.SectionType.LIGHT_AND_DATA;
            if (section.hasOnlyAir() && !hasBlockLight && !hasSkyLight) {
                skipped++;
                continue;
            }

            // Snapshot the DataLayer byte[] immediately so the per-voxel reads
            // below operate on our private copy, not the live light-engine
            // array that c2me's per-dimension Light thread (see c2me's
            // MixinThreadedAnvilChunkStorage) can mutate at any time.
            //
            // IMPERFECT FIX: this shrinks the race window from ~ms (duration of
            // the full convert + mipSection loop reading the live array) to ~us
            // (duration of a single 2 KiB Arrays.copyOf inside DataLayer.copy).
            // If the light thread happens to write WHILE our copy is in flight,
            // the snapshot can still straddle a single update — individual byte
            // reads remain atomic so the snapshot is always internally valid,
            // but it may mix pre- and post-write bytes from one update event.
            //
            // The only race-free fix is to route the snapshot read onto c2me's
            // light thread (single-threaded executor → serialises with its own
            // writes). That adds chunk-load latency and creates a c2me/voxy
            // lock-ordering surface we currently don't have. Not worth it for
            // the residual visible impact, which is self-healing on the next
            // save cycle anyway. Revisit if the snapshot-window race ever
            // produces user-visible LOD lighting artefacts.
            DataLayer bl = hasBlockLight ? blockLayer.getDataLayerData(sectionPos) : null;
            DataLayer sl = hasSkyLight ? skyLayer.getDataLayerData(sectionPos) : null;
            if (bl != null) bl = bl.copy();
            if (sl != null) sl = sl.copy();

            var vs = SECTION_CACHE.get().setPosition(cx, sectionY, cz);

            if (section.hasOnlyAir() && bl == null && sl == null) {
                WorldUpdater.insertUpdate(engine, vs.zero());
                processed++;
                continue;
            }

            WorldConversionFactory.convert(vs, mapper, section.getStates(), section.getBiomes(),
                    lightingSupplier(bl, sl));
            WorldConversionFactory.mipSection(vs, mapper);
            WorldUpdater.insertUpdate(engine, vs);
            processed++;
        }
        // Mark the chunk after every section has been processed (or skipped).
        // Persisted to voxy storage; survives world reloads. Used by the
        // distant-gen walker to skip already-LOD'd chunks and by /voxy import
        // to skip already-imported chunks.
        engine.markChunkProcessed(cx, cz);
        return new int[] {processed, skipped};
    }

    @NotNull
    private static ILightingSupplier lightingSupplier(DataLayer bl, DataLayer sl) {
        boolean hasBl = bl != null && !bl.isEmpty();
        boolean hasSl = sl != null && !sl.isEmpty();
        if (hasBl && hasSl) {
            return (x, y, z) -> {
                int block = Math.min(15, bl.get(x, y, z));
                int sky = Math.min(15, sl.get(x, y, z));
                return (byte) (sky | (block << 4));
            };
        } else if (hasBl) {
            return (x, y, z) -> (byte) ((Math.min(15, bl.get(x, y, z))) << 4);
        } else if (hasSl) {
            return (x, y, z) -> (byte) Math.min(15, sl.get(x, y, z));
        } else {
            return (x, y, z) -> (byte) 0;
        }
    }

    public int queueDepth() {
        return this.service.numJobs();
    }

    public void shutdown() {
        this.service.shutdown();
        this.queue.clear();
    }
}
