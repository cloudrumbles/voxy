package me.cortex.voxy.common.world.service;

import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.thread.Service;
import me.cortex.voxy.common.thread.ServiceManager;
import me.cortex.voxy.common.voxelization.ILightingSupplier;
import me.cortex.voxy.common.voxelization.VoxelizedSection;
import me.cortex.voxy.common.voxelization.WorldConversionFactory;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldUpdater;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.lighting.LayerLightSectionStorage;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;

public class VoxelIngestService {
    private static final ThreadLocal<VoxelizedSection> SECTION_CACHE = ThreadLocal.withInitial(VoxelizedSection::createEmpty);
    private final Service service;
    // Holds a strong ref to MC's LevelChunkSection until processJob drains the
    // queue. Two concerns the real fix would address (both have the same
    // remedy: deep-copy palette + biome containers at enqueue time):
    //
    // (1) THREAD SAFETY: if MC mutates the LevelChunkSection between enqueue
    // and process, the worker reads inconsistent state. Server-thread enqueue
    // + worker-thread process is the usual ordering hazard.
    //
    // (2) MEMORY PIN: while queued, voxy holds the LevelChunkSection (mostly
    // ~10-30 KiB of PalettedContainer payload) so MC can't free chunks it
    // wants to unload. Under sustained queue backup (world-load flush,
    // fly-through outrunning ingest, distant-gen flush) this can pin hundreds
    // of MiB.
    //
    // Both are bounded in normal operation (ingest rate >> typical input
    // rate; backed-up chunks are usually kept loaded anyway). Real fix
    // deferred until empirically problematic — copy() costs CPU + GC pressure
    // on every chunk-load.
    private record IngestSection(int cx, int cy, int cz, WorldEngine world, LevelChunkSection section, DataLayer blockLight, DataLayer skyLight){}
    private final ConcurrentLinkedDeque<IngestSection> ingestQueue = new ConcurrentLinkedDeque<>();

    // Pool of byte[DataLayer.SIZE] used to back the defensive copies of
    // BlockLight/SkyLight DataLayers we hand to ingest workers. Avoids
    // allocating a fresh 2 KB byte[] for every section read during chunk
    // load -- which on a world-load with thousands of chunks adds up to
    // hundreds of MB of transient allocations and GC pressure. The pool
    // is bounded so it never holds more than the cap; over the cap the
    // released buffer is just dropped for GC.
    private static final int LIGHT_BUFFER_POOL_CAP = 256;
    private static final AtomicInteger LIGHT_BUFFER_POOL_COUNT = new AtomicInteger(0);
    private static final ConcurrentLinkedDeque<byte[]> LIGHT_BUFFER_POOL = new ConcurrentLinkedDeque<>();

    // Returns a DataLayer whose backing byte[] came from LIGHT_BUFFER_POOL
    // (or was freshly allocated), with src's contents copied in. After the
    // ingest worker consumes the section, the buffer is returned to the
    // pool via releasePooledLightLayer.
    private static DataLayer copyToPooledLightLayer(DataLayer src) {
        if (src == null) return null;
        byte[] buf = LIGHT_BUFFER_POOL.poll();
        if (buf != null) {
            LIGHT_BUFFER_POOL_COUNT.decrementAndGet();
        } else {
            buf = new byte[DataLayer.SIZE];
        }
        System.arraycopy(src.getData(), 0, buf, 0, DataLayer.SIZE);
        return new DataLayer(buf);
    }

    private static void releasePooledLightLayer(DataLayer wrapper) {
        if (wrapper == null) return;
        byte[] buf = wrapper.getData();
        if (buf == null || buf.length != DataLayer.SIZE) return;
        if (LIGHT_BUFFER_POOL_COUNT.get() < LIGHT_BUFFER_POOL_CAP) {
            LIGHT_BUFFER_POOL.add(buf);
            LIGHT_BUFFER_POOL_COUNT.incrementAndGet();
        }
    }

    public VoxelIngestService(ServiceManager pool) {
        this.service = pool.createServiceNoCleanup(()->this::processJob, 5000, "Ingest service");
    }

    private void processJob() {
        var task = this.ingestQueue.pop();
        try {
            task.world.markActive();

            var section = task.section;
            var vs = SECTION_CACHE.get().setPosition(task.cx, task.cy, task.cz);

            if (section.hasOnlyAir() && task.blockLight==null && task.skyLight==null) {//If the chunk section has lighting data, propagate it
                WorldUpdater.insertUpdate(task.world, vs.zero());
            } else {
                VoxelizedSection csec = WorldConversionFactory.convert(
                        vs,
                        task.world.getMapper(),
                        section.getStates(),
                        section.getBiomes(),
                        getLightingSupplier(task)
                );
                WorldConversionFactory.mipSection(csec, task.world.getMapper());
                WorldUpdater.insertUpdate(task.world, csec);
            }
        } finally {
            // Reclaim the lighting buffers for the next task. Safe even on
            // the rawIngest path: those callers always pass fresh DataLayer
            // copies (see callers in MixinClientLevel /
            // MixinRenderSectionManager), so the byte[] is unshared by
            // contract.
            releasePooledLightLayer(task.blockLight);
            releasePooledLightLayer(task.skyLight);
        }
    }

    @NotNull
    private static ILightingSupplier getLightingSupplier(IngestSection task) {
        ILightingSupplier supplier = (x,y,z) -> (byte) 0;
        var sla = task.skyLight;
        var bla = task.blockLight;
        boolean sl = sla != null && !sla.isEmpty();
        boolean bl = bla != null && !bla.isEmpty();
        if (sl || bl) {
            if (sl && bl) {
                supplier = (x,y,z)-> {
                    int block = Math.min(15,bla.get(x, y, z));
                    int sky = Math.min(15,sla.get(x, y, z));
                    return (byte) (sky|(block<<4));
                };
            } else if (bl) {
                supplier = (x,y,z)-> {
                    int block = Math.min(15,bla.get(x, y, z));
                    int sky = 0;
                    return (byte) (sky|(block<<4));
                };
            } else {
                supplier = (x,y,z)-> {
                    int block = 0;
                    int sky = Math.min(15,sla.get(x, y, z));
                    return (byte) (sky|(block<<4));
                };
            }
        }
        return supplier;
    }

    private static boolean shouldIngestSection(LevelChunkSection section, int cx, int cy, int cz) {
        return true;
    }

    public boolean enqueueIngest(WorldEngine engine, LevelChunk chunk) {
        if (!this.service.isLive()) {
            return false;
        }
        if (!engine.isLive()) {
            throw new IllegalStateException("Tried inserting chunk into WorldEngine that was not alive");
        }

        engine.markActive();

        var lightingProvider = chunk.getLevel().getLightEngine();
        boolean gotLighting = false;

        int i = chunk.getMinSection() - 1;
        boolean allEmpty = true;
        for (var section : chunk.getSections()) {
            i++;
            if (section == null || !shouldIngestSection(section, chunk.getPos().x, i, chunk.getPos().z)) continue;
            allEmpty&=section.hasOnlyAir();
            //if (section.isEmpty()) continue;
            var pos = SectionPos.of(chunk.getPos(), i);
            if (lightingProvider.getDebugSectionType(LightLayer.SKY, pos) != LayerLightSectionStorage.SectionType.LIGHT_AND_DATA && lightingProvider.getDebugSectionType(LightLayer.BLOCK, pos) != LayerLightSectionStorage.SectionType.LIGHT_AND_DATA)
                continue;
            gotLighting = true;
        }

        if (allEmpty&&!gotLighting) {
            //Special case all empty chunk columns, we need to clear it out
            i = chunk.getMinSection() - 1;
            for (var section : chunk.getSections()) {
                i++;
                if (section == null || !shouldIngestSection(section, chunk.getPos().x, i, chunk.getPos().z)) continue;
                engine.markActive();
                this.ingestQueue.add(new IngestSection(chunk.getPos().x, i, chunk.getPos().z, engine, section, null, null));
                try {
                    this.service.execute();
                } catch (Exception e) {
                    Logger.error("Executing had an error: assume shutting down, aborting",e);
                    break;
                }
            }
            // Mark the chunk as processed — all-empty sections have been
            // queued as zero-inserts. Persists across sessions so the
            // distant-gen walker and /voxy import will skip it later.
            engine.markChunkProcessed(chunk.getPos().x, chunk.getPos().z);
        }

        if (!gotLighting) {
            return false;
        }

        var blp = lightingProvider.getLayerListener(LightLayer.BLOCK);
        var slp = lightingProvider.getLayerListener(LightLayer.SKY);


        i = chunk.getMinSection() - 1;
        for (var section : chunk.getSections()) {
            i++;
            if (section == null || !shouldIngestSection(section, chunk.getPos().x, i, chunk.getPos().z)) continue;
            //if (section.isEmpty()) continue;
            var pos = SectionPos.of(chunk.getPos(), i);

            var bl = copyToPooledLightLayer(blp.getDataLayerData(pos));
            var sl = copyToPooledLightLayer(slp.getDataLayerData(pos));

            //If its null for either, assume failure to obtain lighting and ignore section
            //if (blNone && slNone) {
            //    continue;
            //}
            engine.markActive();
            // See IngestSection comment: holding section strongly here pins
            // both MC's chunk memory and exposes us to concurrent mutation.
            // Both fixable by deep-copying section.getStates()/getBiomes()
            // here on the server thread (calling thread = mutator => race-
            // free copy). Deferred pending empirical pressure.
            this.ingestQueue.add(new IngestSection(chunk.getPos().x, i, chunk.getPos().z, engine, section, bl, sl));
            try {
                this.service.execute();
            } catch (Exception e) {
                Logger.error("Executing had an error: assume shutting down, aborting",e);
                break;
            }
        }
        // Mark the chunk as processed — section voxelisation is async but
        // queued; the marker indicates "voxy has accepted this chunk for
        // processing". Used by the distant-gen walker to skip and by
        // /voxy import to dedupe.
        engine.markChunkProcessed(chunk.getPos().x, chunk.getPos().z);
        return true;
    }

    public int getTaskCount() {
        return this.service.numJobs();
    }

    public void shutdown() {
        this.service.shutdown();
    }

    //Utility method to ingest a chunk into the given WorldIdentifier or world
    public static boolean tryIngestChunk(WorldIdentifier worldId, LevelChunk chunk) {
        if (worldId == null) return false;
        var instance = VoxyCommon.getInstance();
        if (instance == null) return false;
        if (!instance.isIngestEnabled(worldId)) return false;
        var engine = instance.getOrCreate(worldId);
        if (engine == null) return false;
        return instance.getIngestService().enqueueIngest(engine, chunk);
    }

    //Try to automatically ingest the chunk into the correct world
    public static boolean tryAutoIngestChunk(LevelChunk chunk) {
        return tryIngestChunk(WorldIdentifier.of(chunk.getLevel()), chunk);
    }

    private boolean rawIngest0(WorldEngine engine, LevelChunkSection section, int x, int y, int z, DataLayer bl, DataLayer sl) {
        this.ingestQueue.add(new IngestSection(x, y, z, engine, section, bl, sl));
        try {
            this.service.execute();
            return true;
        } catch (Exception e) {
            Logger.error("Executing had an error: assume shutting down, aborting",e);
            return false;
        }
    }

    public static boolean rawIngest(WorldIdentifier id, LevelChunkSection section, int x, int y, int z, DataLayer bl, DataLayer sl) {
        if (id == null) return false;
        var engine = id.getOrCreateEngine();
        if (engine == null) return false;
        return rawIngest(engine, section, x, y, z, bl, sl);
    }

    public static boolean rawIngest(WorldEngine engine, LevelChunkSection section, int x, int y, int z, DataLayer bl, DataLayer sl) {
        if (!shouldIngestSection(section, x, y, z)) return false;
        if (engine.instanceIn == null) return false;
        if (!engine.instanceIn.isIngestEnabled(null)) return false;//TODO: dont pass in null
        return engine.instanceIn.getIngestService().rawIngest0(engine, section, x, y, z, bl, sl);
    }
}
