package me.cortex.voxy.common.config.section;

import me.cortex.voxy.common.config.IMappingStorage;
import me.cortex.voxy.common.config.IStoredSectionPositionIterator;
import me.cortex.voxy.common.world.WorldSection;

public abstract class SectionStorage implements IMappingStorage, IStoredSectionPositionIterator {
    public abstract int loadSection(WorldSection into);

    public abstract void saveSection(WorldSection section);

    // Cheap existence check used by importers to skip work for sections that
    // voxy already has saved. Implementations should avoid reading or
    // decompressing the value bytes when possible.
    public abstract boolean containsSection(long key);

    // Write a 0/1-byte sentinel value at the given key. Used by voxy's
    // per-chunk "this chunk has been processed" marker mechanism (keys at
    // WorldEngine.CHUNK_MARKER_LOD). The value is never read back — only
    // existence matters (queried via containsSection on the same key).
    public abstract void markChunkProcessed(long key);
}
