package me.cortex.voxy.common.config.section;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.config.ConfigBuildCtx;
import me.cortex.voxy.common.config.storage.StorageBackend;
import me.cortex.voxy.common.config.storage.StorageConfig;
import me.cortex.voxy.common.util.ThreadLocalMemoryBuffer;
import me.cortex.voxy.common.world.SaveLoadSystem3;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.common.world.other.Mapper;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.function.LongConsumer;

public class SectionSerializationStorage extends SectionStorage {
    private final StorageBackend backend;
    public SectionSerializationStorage(StorageBackend storageBackend) {
        this.backend = storageBackend;
    }

    private static final ThreadLocalMemoryBuffer MEMORY_CACHE = new ThreadLocalMemoryBuffer(SaveLoadSystem3.BIGGEST_SERIALIZED_SECTION_SIZE + 1024);
    // 1-byte buffer used as the value for chunk-marker writes. Value is
    // never read; only existence of the key matters.
    private static final ThreadLocalMemoryBuffer MARKER_BUFFER = new ThreadLocalMemoryBuffer(1);

    public int loadSection(WorldSection into) {
        var data = this.backend.getSectionData(into.key, MEMORY_CACHE.get().createUntrackedUnfreeableReference());
        if (data != null) {
            if (!SaveLoadSystem3.deserialize(into, data)) {
                this.backend.deleteSectionData(into.key);
                //TODO: regenerate the section from children
                Arrays.fill(into._unsafeGetRawDataArray(), Mapper.AIR);
                // Filled with Mapper.AIR (= 0L), not AIR_FILL_VALUE; the
                // air-cache invariant is specifically the latter, so this
                // array must not be tagged as known-air. The caller will
                // map status<0 to status=1 and immediately invoke
                // fillWithAir(), which writes the correct AIR_FILL_VALUE.
                into.markDataDirty();
                Logger.error("Section " + into.lvl + ", " + into.x + ", " + into.y + ", " + into.z + " was unable to load, removing");
                return -1;
            } else {
                return 0;
            }
        } else {
            //TODO: if we need to fetch an lod from a server, send the request here and block until the request is finished
            // the response should be put into the local db so that future data can just use that
            // the server can also send arbitrary updates to the client for arbitrary lods
            return 1;
        }
    }


    @Override
    public void saveSection(WorldSection section) {
        var saveData = SaveLoadSystem3.serialize(section);
        this.backend.setSectionData(section.key, saveData);
        //Note that savedData isnt freed (the save system uses a cache)
    }

    @Override
    public boolean containsSection(long key) {
        return this.backend.containsSectionData(key);
    }

    @Override
    public void markChunkProcessed(long key) {
        // Write a 1-byte sentinel via the storage backend. RocksDB's put
        // copies the value bytes, so the thread-local buffer is safe to
        // reuse immediately after.
        this.backend.setSectionData(key, MARKER_BUFFER.get().createUntrackedUnfreeableReference());
    }

    @Override
    public void putIdMapping(int id, ByteBuffer data) {
        this.backend.putIdMapping(id, data);
    }

    @Override
    public Int2ObjectOpenHashMap<byte[]> getIdMappingsData() {
        return this.backend.getIdMappingsData();
    }

    @Override
    public void flush() {
        this.backend.flush();
    }

    @Override
    public void close() {
        this.backend.close();
    }

    @Override
    public void iteratePositions(int level, LongConsumer consumer) {
        this.backend.iteratePositions(level, consumer);
    }

    public static class Config extends SectionStorageConfig {
        public StorageConfig storage;

        @Override
        public SectionStorage build(ConfigBuildCtx ctx) {
            return new SectionSerializationStorage(this.storage.build(ctx));
        }

        public static String getConfigTypeName() {
            return "Serializer";
        }
    }
}
