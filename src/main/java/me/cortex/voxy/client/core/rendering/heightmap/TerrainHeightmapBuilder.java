package me.cortex.voxy.client.core.rendering.heightmap;

import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.common.world.other.Mapper;

// Builds one ring of the LOD terrain heightmap clipmap — the data source for
// the horizon-silhouette shadow plan ("cardboard cutout" occlusion of sun/moon).
//
// A single uniform map cannot serve this well: the nearest LOD terrain is the
// most detailed thing on screen, yet a flat 16 m map gives it exactly the same
// shadow resolution as terrain 4 km away. So the map is a CLIPMAP — concentric
// rings of equal texel count, each one voxy LOD level finer than the one
// outside it. Ring i reads level (i+1), so its texel is 2^(i+1) blocks:
//
//   ring 0  level 1   2 m/texel   1024 blocks across   (+/- 512)
//   ring 1  level 2   4 m/texel   2048 blocks across   (+/- 1024)
//   ring 2  level 3   8 m/texel   4096 blocks across   (+/- 2048)
//   ring 3  level 4  16 m/texel   8192 blocks across   (+/- 4096)
//
// Every ring is SIDE_TEXELS square and 16 voxy sections across regardless of
// level, because a section is always 32 cells: the scan is the same shape and
// cost per ring, only the scale changes.
//
// Origins are snapped to whole sections in ABSOLUTE WORLD coordinates, never to
// the camera. Re-centring then only slides a window across a fixed world
// lattice, so the value at a given world position is identical before and after
// and a rebuild is invisible. Snapping to the camera instead would resample the
// whole field on every rebuild and make the shadows jitter as you walk.
//
// Each column carries two heights (see the ground/canopy split below): what it
// casts with, and what it receives on.
public class TerrainHeightmapBuilder {
    public static final short MISSING = Short.MIN_VALUE;

    public static final int RINGS = 4;
    public static final int SIDE_TEXELS = 512;
    private static final int CELLS_PER_SECTION = 32;
    private static final int SIDE_SECTIONS = SIDE_TEXELS / CELLS_PER_SECTION; // 16
    // 1.20.1 world bounds. Used only to bound the vertical section scan.
    private static final int MIN_WORLD_Y = -64;
    private static final int MAX_WORLD_Y = 320;

    public static int levelOf(int ring) {
        return ring + 1;
    }

    public static int blocksPerTexel(int ring) {
        return 1 << levelOf(ring);
    }

    public static int sectionBlocks(int ring) {
        return CELLS_PER_SECTION * blocksPerTexel(ring);
    }

    public static int spanBlocks(int ring) {
        return SIDE_TEXELS * blocksPerTexel(ring);
    }

    // Section-snapped origin for a ring centred on a camera block position.
    public static int originSectionFor(int ring, int cameraBlock) {
        return Math.floorDiv(cameraBlock, sectionBlocks(ring)) - SIDE_SECTIONS / 2;
    }

    private final int ring;
    private final short[] heights;
    // Ground heights: the same scan ignoring foliage, so the top of a 250-block
    // tree does not become the "surface" of the column it stands in.
    // `heights` casts shadows, `groundHeights` receives them.
    private final short[] groundHeights;
    private final int originSectionX;
    private final int originSectionZ;

    // Foliage classification cache, indexed by voxy block id.
    // 0 = unknown, 1 = ground, 2 = foliage. Shared across the rings of one
    // rebuild pass so the tag lookups are paid for once, not four times.
    public static final class FoliageCache {
        private byte[] entries = new byte[1024];

        private boolean isFoliage(Mapper mapper, int blockId) {
            if (blockId < 0) return false;
            if (blockId >= this.entries.length) {
                this.entries = java.util.Arrays.copyOf(this.entries, Math.max(blockId + 1, this.entries.length * 2));
            }
            byte cached = this.entries[blockId];
            if (cached != 0) return cached == 2;

            boolean foliage = false;
            try {
                var state = mapper.getBlockStateFromBlockId(blockId);
                foliage = state != null && (
                        state.is(net.minecraft.tags.BlockTags.LEAVES)
                        || state.is(net.minecraft.tags.BlockTags.LOGS)
                        || state.is(net.minecraft.tags.BlockTags.SAPLINGS)
                        || state.is(net.minecraft.tags.BlockTags.FLOWERS)
                        || state.is(net.minecraft.tags.BlockTags.TALL_FLOWERS)
                        || state.is(net.minecraft.tags.BlockTags.CROPS));
            } catch (Throwable ignored) {
                // Unmapped or half-registered id: treat as ground, which is the
                // conservative direction (it can only shorten a shadow, never
                // punch a lit hole in one).
            }
            this.entries[blockId] = (byte) (foliage ? 2 : 1);
            return foliage;
        }
    }

    private TerrainHeightmapBuilder(int ring, int originSectionX, int originSectionZ) {
        this.ring = ring;
        this.originSectionX = originSectionX;
        this.originSectionZ = originSectionZ;
        this.heights = new short[SIDE_TEXELS * SIDE_TEXELS];
        this.groundHeights = new short[this.heights.length];
        java.util.Arrays.fill(this.heights, MISSING);
        java.util.Arrays.fill(this.groundHeights, MISSING);
    }

    public int ring() {
        return this.ring;
    }

    public int originBlockX() {
        return this.originSectionX * sectionBlocks(this.ring);
    }

    public int originBlockZ() {
        return this.originSectionZ * sectionBlocks(this.ring);
    }

    public short[] rawHeights() {
        return this.heights;
    }

    public short[] rawGroundHeights() {
        return this.groundHeights;
    }

    // Build one ring at a section-snapped origin. Safe to call from any thread;
    // uses acquireIfExists so it never forces section loads or generation.
    public static TerrainHeightmapBuilder build(WorldEngine engine, int ring, int originSectionX, int originSectionZ, FoliageCache foliage) {
        var map = new TerrainHeightmapBuilder(ring, originSectionX, originSectionZ);
        int level = levelOf(ring);
        int sectionSpan = sectionBlocks(ring);
        int minSectionY = Math.floorDiv(MIN_WORLD_Y, sectionSpan);
        int maxSectionY = Math.floorDiv(MAX_WORLD_Y, sectionSpan);
        var mapper = engine.getMapper();

        int sectionsScanned = 0;
        for (int sz = 0; sz < SIDE_SECTIONS; sz++) {
            for (int sx = 0; sx < SIDE_SECTIONS; sx++) {
                // Higher section layer first: a column already resolved from a
                // higher layer never needs the lower one.
                for (int sy = maxSectionY; sy >= minSectionY; sy--) {
                    WorldSection section = engine.acquireIfExists(level, originSectionX + sx, sy, originSectionZ + sz);
                    if (section == null) continue;
                    try {
                        map.scanSection(mapper, foliage, section, sx, sy, sz);
                    } finally {
                        section.release();
                    }
                    sectionsScanned++;
                }
            }
        }

        // A column made entirely of foliage (a tree over water, a floating
        // canopy) has no ground of its own. Fall back to its full height so the
        // two channels stay in step: they are MISSING together or present
        // together, which is the invariant the shader's no-data test relies on.
        for (int i = 0; i < map.heights.length; i++) {
            if (map.groundHeights[i] == MISSING) {
                map.groundHeights[i] = map.heights[i];
            }
        }

        Logger.debug("Heightmap ring " + ring + " (level " + level + ", " + blocksPerTexel(ring)
                + " m/texel, span " + spanBlocks(ring) + "): " + sectionsScanned + " sections scanned");
        return map;
    }

    private void scanSection(Mapper mapper, FoliageCache foliage, WorldSection section, int sx, int sy, int sz) {
        final long[] data = section._unsafeGetRawDataArray();
        final int blocksPerTexel = blocksPerTexel(this.ring);
        for (int cz = 0; cz < CELLS_PER_SECTION; cz++) {
            int rowBase = (sz * CELLS_PER_SECTION + cz) * SIDE_TEXELS + sx * CELLS_PER_SECTION;
            for (int cx = 0; cx < CELLS_PER_SECTION; cx++) {
                int idx = rowBase + cx;
                // Both channels resolve in one descending pass. A column can
                // have its canopy height settled by a higher section layer while
                // its ground still lies in a lower one, so each is tracked
                // independently and the column is only finished when both are
                // known.
                boolean needFull = this.heights[idx] == MISSING;
                boolean needGround = this.groundHeights[idx] == MISSING;
                if (!needFull && !needGround) continue;
                int foliageCellAbove = Integer.MIN_VALUE;
                for (int cy = CELLS_PER_SECTION - 1; cy >= 0; cy--) {
                    long cell = data[(cy << 10) | (cz << 5) | cx];
                    if (Mapper.isAir(cell)) continue;
                    // Top face of this cell, in blocks.
                    short height = (short) ((sy * CELLS_PER_SECTION + cy + 1) * blocksPerTexel);
                    if (needFull) {
                        this.heights[idx] = height;
                        needFull = false;
                    }
                    if (needGround) {
                        if (foliage.isFoliage(mapper, Mapper.getBlockId(cell))) {
                            foliageCellAbove = cy;
                        } else {
                            // A cell carries ONE block id, so a cell holding both
                            // soil and the undergrowth standing on it can mip to
                            // either. When foliage occupies the cell directly
                            // above the highest solid one, the real ground surface
                            // lies somewhere inside that foliage cell, so take its
                            // top rather than the solid cell's.
                            //
                            // Erring upward is the safe direction. Placing ground a
                            // cell too low hands every fragment in the column a
                            // cell of false "climbed out of the shadow volume"
                            // credit, which lights forest floors and the leaves on
                            // them right in the middle of a shadow. Erring high
                            // merely costs a real clifftop a little of the credit
                            // it had earned.
                            int groundCy = foliageCellAbove == cy + 1 ? cy + 1 : cy;
                            this.groundHeights[idx] = (short) ((sy * CELLS_PER_SECTION + groundCy + 1) * blocksPerTexel);
                            needGround = false;
                        }
                    }
                    if (!needFull && !needGround) break;
                }
            }
        }
    }

    // Debug: write both channels as PNGs on a SHARED height scale so they can be
    // compared directly (magenta = no data). The canopy map next to the ground
    // map is how foliage exclusion gets verified: tall isolated spikes should be
    // present in one and absent from the other.
    public java.io.File dumpPng(java.io.File target) throws java.io.IOException {
        int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
        for (short h : this.heights) {
            if (h == MISSING) continue;
            min = Math.min(min, h);
            max = Math.max(max, h);
        }
        if (min > max) { min = 0; max = 1; } // entirely missing

        writeChannelPng(this.heights, min, max, target);
        String path = target.getAbsolutePath();
        int dot = path.lastIndexOf('.');
        var groundTarget = new java.io.File((dot < 0 ? path : path.substring(0, dot)) + "-ground.png");
        writeChannelPng(this.groundHeights, min, max, groundTarget);

        int groundMax = Integer.MIN_VALUE;
        for (short h : this.groundHeights) {
            if (h != MISSING) groundMax = Math.max(groundMax, h);
        }
        Logger.info("Heightmap ring " + this.ring + " (" + blocksPerTexel(this.ring) + " m/texel) dumped to " + path
                + " (height range " + min + ".." + max + "); ground channel to " + groundTarget.getAbsolutePath()
                + " (max " + (groundMax == Integer.MIN_VALUE ? "none" : groundMax) + ")");
        return target;
    }

    private void writeChannelPng(short[] channel, int min, int max, java.io.File target) throws java.io.IOException {
        var img = new java.awt.image.BufferedImage(SIDE_TEXELS, SIDE_TEXELS, java.awt.image.BufferedImage.TYPE_INT_RGB);
        for (int z = 0; z < SIDE_TEXELS; z++) {
            for (int x = 0; x < SIDE_TEXELS; x++) {
                short h = channel[z * SIDE_TEXELS + x];
                int rgb;
                if (h == MISSING) {
                    rgb = 0xFF00FF;
                } else {
                    int g = max == min ? 255 : Math.max(0, Math.min(255, (h - min) * 255 / (max - min)));
                    rgb = (g << 16) | (g << 8) | g;
                }
                img.setRGB(x, z, rgb);
            }
        }
        javax.imageio.ImageIO.write(img, "png", target);
    }
}
