package me.cortex.voxy.common.world.other;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;

/**
 * Runtime-only synthetic block-id space for multi-cell block-entity LOD "slices".
 *
 * BACKGROUND
 * ----------
 * A voxel cell stores one 20-bit block id (see {@link Mapper}). Real ids are assigned
 * densely from 0 upward as blockstates are encountered and are PERSISTED per world
 * (id <-> blockstate, on disk). A multi-cell block entity (e.g. a Create water wheel
 * whose geometry overhangs into neighbouring cells) cannot be stored by a single real id,
 * because each cell holds exactly one id.
 *
 * This registry hands out SYNTHETIC ids for the overhang cells. A synthetic id means
 * "the (dx,dy,dz) sub-cell slice of the multi-cell block whose real id is sourceBlockId".
 *
 * KEY PROPERTIES
 *  - NEVER PERSISTED. Synthetic ids are only ever written into an in-memory copy of a
 *    section's voxel array at mesh time, never serialized to disk. On the next session
 *    they are re-derived deterministically from the same persisted real ids.
 *  - DETERMINISTIC / STABLE WITHIN A SESSION. The same (sourceBlockId, dx, dy, dz) always
 *    maps to the same synthetic id for the life of the registry, so the same slice reuses
 *    the same baked model everywhere (the model cache in ModelFactory keys on block id).
 *  - DISJOINT FROM REAL IDS. Synthetic ids live in a reserved high slab of the 20-bit
 *    space ([{@link #BASE}, 2^20)). Real ids (~62k in practice) never reach BASE.
 *
 * LOCATION: lives in `common.world.other` beside {@link Mapper} because it concerns the
 * block-id space (Mapper's domain), and so Mapper can resolve synthetic ids without the
 * common layer depending on the client layer. The slice -> baked-model machinery, the
 * footprint geometry and the per-block handlers live in the client layer.
 *
 * DATA STRUCTURES (all on the Java heap):
 *  - key2id : Long2IntOpenHashMap  — primitive hash map (sourceBlockId,offset)->synthId.
 *             O(1) average lookup/insert. One entry per distinct slice ever needed.
 *  - id2slice : Slice[]            — flat array indexed by (synthId - BASE). O(1) read.
 *  Bound on both: at most {@link #CAPACITY}; in practice dozens to low hundreds. Allocation
 *  happens once per distinct slice per session; thereafter every access is a pure O(1) read.
 *
 * THREAD-SAFETY: meshing runs on a worker pool, so methods are synchronized; contention is
 * negligible (allocation is rare; the synchronized read is a single array load).
 */
public final class MultiCellSliceRegistry {
    public static final MultiCellSliceRegistry INSTANCE = new MultiCellSliceRegistry();

    /** Reserved high slab: ids in [BASE, 2^20) are synthetic. 2^20 - 2^16 = 983040. */
    public static final int BASE = (1 << 20) - (1 << 16);
    /** Number of synthetic ids available: 2^16 = 65536. */
    public static final int CAPACITY = 1 << 16;

    /** True if this block id refers to a synthetic multi-cell slice rather than a real block. */
    public static boolean isSynthetic(int blockId) {
        return blockId >= BASE;
    }

    /**
     * A synthetic slice: the source block it derives from and which cell of that block's
     * footprint this slice represents (block-relative offset, e.g. dx=1 = one cell +X).
     */
    public record Slice(int sourceBlockId, int dx, int dy, int dz) {}

    private final Long2IntOpenHashMap key2id = new Long2IntOpenHashMap();
    private final Slice[] id2slice = new Slice[CAPACITY];
    private int count = 0;

    private MultiCellSliceRegistry() {
        this.key2id.defaultReturnValue(-1);
    }

    // Pack (sourceBlockId in 20 bits, dx/dy/dz as signed 8-bit offsets) into one long key.
    private static long packKey(int sourceBlockId, int dx, int dy, int dz) {
        return (Integer.toUnsignedLong(sourceBlockId) & 0xFFFFF)
                | ((long) (dx & 0xFF) << 20)
                | ((long) (dy & 0xFF) << 28)
                | ((long) (dz & 0xFF) << 36);
    }

    /**
     * Return the synthetic id for this slice, allocating one on first request.
     * Stable for the life of the registry. Throws if the synthetic space is exhausted.
     */
    public synchronized int getOrAllocate(int sourceBlockId, int dx, int dy, int dz) {
        long key = packKey(sourceBlockId, dx, dy, dz);
        int existing = this.key2id.get(key);
        if (existing != -1) {
            return existing;
        }
        if (this.count >= CAPACITY) {
            throw new IllegalStateException("Multi-cell synthetic id space exhausted (" + CAPACITY + ")");
        }
        int id = BASE + this.count;
        this.id2slice[this.count] = new Slice(sourceBlockId, dx, dy, dz);
        this.count++;
        this.key2id.put(key, id);
        return id;
    }

    /** Resolve a synthetic id back to its slice. Caller must pass a synthetic id. */
    public synchronized Slice getSlice(int syntheticId) {
        int idx = syntheticId - BASE;
        if (idx < 0 || idx >= this.count) {
            throw new IndexOutOfBoundsException("Not an allocated synthetic id: " + syntheticId);
        }
        return this.id2slice[idx];
    }

    /** Number of synthetic ids allocated so far this session (diagnostics). */
    public synchronized int allocatedCount() {
        return this.count;
    }
}
