package me.cortex.voxy.client.core.rendering.heightmap;

import me.cortex.voxy.client.core.gl.GlTexture;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.world.WorldEngine;
import org.joml.Vector2i;

import static org.lwjgl.opengl.ARBDirectStateAccess.glTextureParameteri;
import static org.lwjgl.opengl.ARBDirectStateAccess.glTextureSubImage2D;
import static org.lwjgl.opengl.GL11C.*;
import static org.lwjgl.opengl.GL12C.GL_CLAMP_TO_EDGE;
import static org.lwjgl.opengl.GL12C.GL_TEXTURE_WRAP_S;
import static org.lwjgl.opengl.GL12C.GL_TEXTURE_WRAP_T;
import static org.lwjgl.opengl.GL30C.GL_RG;
import static org.lwjgl.opengl.GL30C.GL_RG32F;

// Keeps a GL copy of the LOD terrain heightmap clipmap current as the player
// moves and terrain generates. A daemon thread rebuilds rings off the render
// thread (TerrainHeightmapBuilder, acquireIfExists only — never forces loads);
// the render thread uploads finished rings in tick().
//
// The rings live in ONE 2D texture, stacked vertically: SIDE_TEXELS wide by
// SIDE_TEXELS*RINGS tall, ring i occupying rows [i*SIDE_TEXELS, (i+1)*SIDE_TEXELS).
// Iris has no array-texture sampler type, and an atlas costs nothing here: the
// shader already restricts sampling to each ring's texel-centre lattice, so the
// filter never reaches across a tile boundary, and clipmap rings overlap anyway
// so the half-texel border that costs us is territory the next ring out covers.
//
// Exposed to shader packs (VoxySamplers / VoxyUniforms) as vxTerrainHeightmap
// plus vxHeightmapOrigin0..3 / SideTexels / Rings. RG32F, both channels heights
// in blocks:
//   r = highest terrain of any kind      -- what a column CASTS with
//   g = highest terrain ignoring foliage -- what a column RECEIVES on
// MISSING_HEIGHT where no LOD data exists (treated as non-occluding).
//
// Rings rebuild one at a time, so a rebuild burst is never four scans at once,
// and origins are world-snapped by the builder so a re-centre does not shift the
// sampling lattice — the value at a given world position is identical before and
// after, which is what keeps re-centring invisible.
public class TerrainHeightmapTracker {
    public static final float MISSING_HEIGHT = -10000.0f;

    private static final int RINGS = TerrainHeightmapBuilder.RINGS;
    private static final int SIDE = TerrainHeightmapBuilder.SIDE_TEXELS;
    private static final long STALE_INTERVAL_NS = 20_000_000_000L;

    private record PendingUpload(int ring, float[] data, int originBlockX, int originBlockZ) {}

    private final WorldEngine engine;
    private final Thread builder;
    private volatile boolean running = true;

    // Camera position, written by the render thread, read by the builder.
    private volatile int camBlockX;
    private volatile int camBlockZ;
    private volatile boolean hasCamera = false;

    private final PendingUpload[] pending = new PendingUpload[RINGS];

    // Render-thread state.
    private GlTexture texture;
    private final Vector2i[] ringOrigins = new Vector2i[RINGS];
    private final boolean[] ringUploaded = new boolean[RINGS];
    private boolean allRingsUploaded = false;

    public TerrainHeightmapTracker(WorldEngine engine) {
        this.engine = engine;
        for (int i = 0; i < RINGS; i++) {
            this.ringOrigins[i] = new Vector2i();
        }
        this.builder = new Thread(this::builderLoop, "Voxy terrain heightmap builder");
        this.builder.setDaemon(true);
        this.builder.setPriority(Thread.MIN_PRIORITY);
        this.builder.start();
    }

    private void builderLoop() {
        // Per-ring: camera position the ring was last built at, and when.
        int[] builtCamX = new int[RINGS];
        int[] builtCamZ = new int[RINGS];
        long[] builtAt = new long[RINGS];
        boolean[] built = new boolean[RINGS];
        var foliage = new TerrainHeightmapBuilder.FoliageCache();

        while (this.running) {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                return;
            }
            if (!this.running || !this.hasCamera || !this.engine.isLive()) continue;
            int cx = this.camBlockX;
            int cz = this.camBlockZ;
            long now = System.nanoTime();

            // Pick at most ONE ring per pass, finest first. Finest rings have the
            // tightest coverage so they fall out of date soonest, and doing one at
            // a time is what staggers the scans instead of bursting all four.
            int target = -1;
            for (int ring = 0; ring < RINGS; ring++) {
                if (this.pending[ring] != null) continue; // already waiting on the render thread
                if (!built[ring]) { target = ring; break; }
                // Rebuild once the camera has drifted half a section from where
                // this ring was built. Origins snap to whole sections, so half a
                // section of hysteresis stops a ring thrashing while you stand on
                // a boundary.
                int tolerance = TerrainHeightmapBuilder.sectionBlocks(ring) / 2;
                boolean moved = Math.max(Math.abs(cx - builtCamX[ring]), Math.abs(cz - builtCamZ[ring])) >= tolerance;
                boolean stale = now - builtAt[ring] >= STALE_INTERVAL_NS;
                if (moved || stale) { target = ring; break; }
            }
            if (target < 0) continue;

            try {
                int originSectionX = TerrainHeightmapBuilder.originSectionFor(target, cx);
                int originSectionZ = TerrainHeightmapBuilder.originSectionFor(target, cz);
                var map = TerrainHeightmapBuilder.build(this.engine, target, originSectionX, originSectionZ, foliage);
                short[] heights = map.rawHeights();
                short[] ground = map.rawGroundHeights();
                // Interleaved RG: r = full height (casts), g = ground (receives).
                float[] data = new float[heights.length * 2];
                for (int i = 0; i < heights.length; i++) {
                    data[i * 2] = heights[i] == TerrainHeightmapBuilder.MISSING ? MISSING_HEIGHT : heights[i];
                    data[i * 2 + 1] = ground[i] == TerrainHeightmapBuilder.MISSING ? MISSING_HEIGHT : ground[i];
                }
                this.pending[target] = new PendingUpload(target, data, map.originBlockX(), map.originBlockZ());
                built[target] = true;
                builtCamX[target] = cx;
                builtCamZ[target] = cz;
                builtAt[target] = now;
            } catch (Throwable t) {
                if (this.running && this.engine.isLive()) {
                    Logger.error("Terrain heightmap ring " + target + " build failed: " + t.getMessage(), t);
                }
            }
        }
    }

    // Render thread, GL context current. Records the camera for the builder and
    // uploads any finished rings.
    public void tick(int cameraBlockX, int cameraBlockZ) {
        this.camBlockX = cameraBlockX;
        this.camBlockZ = cameraBlockZ;
        this.hasCamera = true;

        for (int ring = 0; ring < RINGS; ring++) {
            var upload = this.pending[ring];
            if (upload == null) continue;
            this.pending[ring] = null;

            if (this.texture == null) {
                this.texture = new GlTexture().store(GL_RG32F, 1, SIDE, SIDE * RINGS).name("Voxy terrain heightmap clipmap");
                glTextureParameteri(this.texture.id, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
                glTextureParameteri(this.texture.id, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
                glTextureParameteri(this.texture.id, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
                glTextureParameteri(this.texture.id, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
            }
            // Unpack state is global and NativeImage leaves GL_UNPACK_ROW_LENGTH at
            // the width of the last image uploaded; upload.data is exactly
            // 2*SIDE^2 floats with no slack, so any non-default state reads past
            // its end and faults inside the driver.
            glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
            glPixelStorei(GL_UNPACK_SKIP_PIXELS, 0);
            glPixelStorei(GL_UNPACK_SKIP_ROWS, 0);
            glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
            glTextureSubImage2D(this.texture.id, 0, 0, ring * SIDE, SIDE, SIDE, GL_RG, GL_FLOAT, upload.data);

            this.ringOrigins[ring].set(upload.originBlockX, upload.originBlockZ);
            this.ringUploaded[ring] = true;
        }

        if (!this.allRingsUploaded) {
            boolean all = true;
            for (boolean up : this.ringUploaded) all &= up;
            // Consumers see the map as unavailable until EVERY ring has landed.
            // A partially populated clipmap would have rings disagreeing about
            // the same world position, which reads as a hard seam.
            this.allRingsUploaded = all;
        }
    }

    public int getTextureId() {
        return this.texture == null ? 0 : this.texture.id;
    }

    public Vector2i getRingOrigin(int ring) {
        return this.ringOrigins[ring];
    }

    // 0 until the whole clipmap is resident; consumers treat that as "no map".
    public int getSideTexels() {
        return this.allRingsUploaded ? SIDE : 0;
    }

    public int getRings() {
        return RINGS;
    }

    public void free() {
        this.running = false;
        this.builder.interrupt();
        try {
            this.builder.join(2000);
        } catch (InterruptedException ignored) {
        }
        if (this.texture != null) {
            this.texture.free();
            this.texture = null;
        }
    }
}
