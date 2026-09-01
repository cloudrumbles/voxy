package me.cortex.voxy.client.core.model.berbake;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure geometry: given a block entity's captured geometry bounding box (in block-local
 * units, where the block's own cell is [0,1) on each axis), compute the set of integer
 * CELL OFFSETS the geometry overlaps. This is the multi-cell "footprint".
 *
 * This is derived from real captured geometry rather than hand-declared per block, so it
 * is mod-agnostic and self-correcting (same philosophy as the chest/floating-block work):
 * a water wheel whose wheel overhangs into neighbouring cells reports exactly those cells;
 * a block that fits its cell reports only the origin.
 *
 * Coordinate convention: cell offset (dx,dy,dz) is the region [dx,dx+1) x [dy,dy+1) x
 * [dz,dz+1). The block's own cell is (0,0,0). A bbox overlaps offset dx on the X axis iff
 * (minX < dx+1) and (maxX > dx). EPS guards against a bbox that merely touches a cell
 * boundary (e.g. maxX == 1.0 exactly) spuriously claiming the next cell.
 *
 * Pure / stateless / O(volume) where volume = number of covered cells (a handful). No
 * allocation beyond the returned list. Easy to reason about and test in isolation.
 */
public final class MultiCellFootprint {
    private MultiCellFootprint() {}

    private static final float EPS = 1.0e-3f;

    /** A covered cell offset relative to the block's own cell (0,0,0). */
    public record Offset(int dx, int dy, int dz) {
        public boolean isOrigin() { return dx == 0 && dy == 0 && dz == 0; }
    }

    /**
     * Covered cell offsets for the given bbox, clamped to +-maxRadius per axis (safety
     * bound against a pathological/huge model exploding the footprint). Includes the
     * origin (0,0,0) if the bbox overlaps it. Order: ascending y, then z, then x.
     */
    public static List<Offset> coveredCells(float minX, float minY, float minZ,
                                            float maxX, float maxY, float maxZ,
                                            int maxRadius) {
        int xLo = clamp(floorWithEps(minX), maxRadius);
        int xHi = clamp(ceilWithEps(maxX) - 1, maxRadius);
        int yLo = clamp(floorWithEps(minY), maxRadius);
        int yHi = clamp(ceilWithEps(maxY) - 1, maxRadius);
        int zLo = clamp(floorWithEps(minZ), maxRadius);
        int zHi = clamp(ceilWithEps(maxZ) - 1, maxRadius);

        List<Offset> out = new ArrayList<>();
        for (int y = yLo; y <= yHi; y++) {
            for (int z = zLo; z <= zHi; z++) {
                for (int x = xLo; x <= xHi; x++) {
                    out.add(new Offset(x, y, z));
                }
            }
        }
        return out;
    }

    // floor, but a value within EPS above an integer counts as that integer (a bbox
    // starting at exactly 0.0 belongs to cell 0, not cell -1 via float noise).
    private static int floorWithEps(float v) {
        return (int) Math.floor(v + EPS);
    }

    // ceil, but a value within EPS below an integer counts as that integer (a bbox
    // ending at exactly 1.0 reaches cell boundary 1, ceil->1, -1 => last cell 0).
    private static int ceilWithEps(float v) {
        return (int) Math.ceil(v - EPS);
    }

    private static int clamp(int v, int r) {
        if (v < -r) return -r;
        if (v > r) return r;
        return v;
    }
}
