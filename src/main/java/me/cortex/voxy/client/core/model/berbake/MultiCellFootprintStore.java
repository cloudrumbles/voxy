package me.cortex.voxy.client.core.model.berbake;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;

/**
 * Records the captured geometry bounding box (block-local) of each multi-cell block's
 * own (offset-0) BER bake, keyed by its real voxy block id.
 *
 * This is the bridge for the "lazy footprint" sequencing (Decision 1a): the footprint a
 * multi-cell block needs to expand into neighbour cells is derived from its captured
 * geometry, which only exists AFTER its centre bake completes. The bakery writes the
 * bbox here when that bake finishes; the mesh-time stamp ({@code stampMultiCell}) reads
 * it. Until a block's centre has been baked, the lookup misses and the section simply
 * isn't expanded yet — it re-meshes once the bake lands (voxy already re-meshes on model
 * completion via the IdNotYetComputed retry path), and the footprint is then available.
 *
 * Runtime-only, never persisted. Bounded by the number of distinct multi-cell block ids
 * (a handful). Read-mostly: one write per such block per session, many reads.
 */
public final class MultiCellFootprintStore {
    public static final MultiCellFootprintStore INSTANCE = new MultiCellFootprintStore();

    /** A captured block-local bbox. */
    public record Box(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        // Geometry must reach at least this far past the cell before we stamp a synthetic
        // slice into a neighbour. This gate runs ONLY on the curated multi-cell allowlist
        // (furniture/leaf noise is never registered, so the old "0.5 to dodge noise" floor
        // is unnecessary) — its only job is to skip a trusted block whose geometry happens
        // to fit. 0.25 = a quarter cell: below large_cogwheel (0.437) and crushing_wheel
        // (0.570) so they expand, above small cogwheel (0.056) / water_wheel hub (0.0) so
        // those bake real geometry in their own cell without spurious slices.
        private static final float REACH = 0.25f;

        /** True if the geometry reaches >= half a cell into a neighbour on any axis. */
        public boolean overflowsCell() {
            return minX < -REACH || minY < -REACH || minZ < -REACH
                    || maxX > 1f + REACH || maxY > 1f + REACH || maxZ > 1f + REACH;
        }
    }

    // blockId -> bbox. fastutil int-keyed map; guarded by the instance monitor because
    // bakes complete on a worker thread while meshing reads on the mesh-gen workers.
    private final Int2ObjectOpenHashMap<Box> id2box = new Int2ObjectOpenHashMap<>();

    private MultiCellFootprintStore() {}

    public synchronized void record(int blockId, float minX, float minY, float minZ,
                                    float maxX, float maxY, float maxZ) {
        this.id2box.put(blockId, new Box(minX, minY, minZ, maxX, maxY, maxZ));
    }

    /** The recorded bbox for this block id, or null if its centre hasn't been baked yet. */
    public synchronized Box get(int blockId) {
        return this.id2box.get(blockId);
    }
}
