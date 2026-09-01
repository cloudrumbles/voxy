package me.cortex.voxy.common.config.storage;

import me.cortex.voxy.common.config.IMappingStorage;
import me.cortex.voxy.common.config.IStoredSectionPositionIterator;
import me.cortex.voxy.common.util.MemoryBuffer;
import me.cortex.voxy.common.util.ThreadLocalMemoryBuffer;

import java.util.ArrayList;
import java.util.List;

public abstract class StorageBackend implements IMappingStorage, IStoredSectionPositionIterator {

    //Implementation may use the scratch buffer as the return value, it MUST NOT free the scratch buffer
    public abstract MemoryBuffer getSectionData(long key, MemoryBuffer scratch);

    public abstract void setSectionData(long key, MemoryBuffer data);

    public abstract void deleteSectionData(long key);

    // Generous sizing for the default containsSectionData path; the real value
    // size is bounded by SaveLoadSystem3.BIGGEST_SERIALIZED_SECTION_SIZE (~512KB).
    private static final ThreadLocalMemoryBuffer CONTAINS_SCRATCH = new ThreadLocalMemoryBuffer(1024L * 1024L);

    // Returns true iff this backend currently has a value stored under the given
    // section key. Used by importers to skip work for already-present sections.
    // The default implementation does a full value read into a thread-local
    // scratch; backends with a cheaper existence check (e.g. RocksDB's per-key
    // get with a tiny value buffer that skips the storage-layer decompression)
    // should override.
    public boolean containsSectionData(long key) {
        return this.getSectionData(key, CONTAINS_SCRATCH.get().createUntrackedUnfreeableReference()) != null;
    }

    public abstract void flush();

    public abstract void close();

    public List<StorageBackend> getChildBackends() {
        return List.of();
    }

    public final List<StorageBackend> collectAllBackends() {
        List<StorageBackend> backends = new ArrayList<>();
        backends.add(this);
        for (var child : this.getChildBackends()) {
            backends.addAll(child.collectAllBackends());
        }
        return backends;
    }
}
