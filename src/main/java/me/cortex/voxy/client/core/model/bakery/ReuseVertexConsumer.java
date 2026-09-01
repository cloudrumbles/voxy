package me.cortex.voxy.client.core.model.bakery;


import me.cortex.voxy.common.util.MemoryBuffer;
import net.minecraft.client.renderer.block.model.BakedQuad;
import org.lwjgl.system.MemoryUtil;

import static me.cortex.voxy.client.core.model.bakery.BudgetBufferRenderer.VERTEX_FORMAT_SIZE;

import com.mojang.blaze3d.vertex.VertexConsumer;

public final class ReuseVertexConsumer implements VertexConsumer {
    private MemoryBuffer buffer = new MemoryBuffer(8192);
    private long ptr;
    private int count;
    private int defaultMeta;

    public boolean anyShaded;
    public boolean anyDarkendTex;

    // Geometry bounding box (block-local), tracked across every emitted vertex so the
    // multi-cell footprint can be derived from real captured geometry. Only meaningful
    // for an offset-0 (identity) capture, where coords are true block-local; the caller
    // reads these immediately after such a bake. Inf/-Inf when nothing was emitted.
    public float minX, minY, minZ;
    public float maxX, maxY, maxZ;

    // Position offset applied to every emitted vertex. Used for multi-cell SLICE bakes:
    // setting it to (-dx,-dy,-dz) slides the geometry occupying cell (dx,dy,dz) into the
    // [0,1] capture cell, so the rest falls outside the orthographic viewport and is
    // GPU-clipped. Applies uniformly to both the static quad() path and the BER path
    // (BER MultiBufferSource -> vertex()). Zero for an ordinary bake. The bbox above
    // tracks PRE-offset coords so footprint measurement is unaffected.
    public float offX, offY, offZ;

    public ReuseVertexConsumer() {
        this.reset();
    }

    public ReuseVertexConsumer setDefaultMeta(int meta) {
        this.defaultMeta = meta;
        return this;
    }

    // 1.20.1 VertexConsumer uses vertex(double, double, double)
    @Override
    public VertexConsumer vertex(double x, double y, double z) {
        this.ensureCanPut();
        this.ptr += VERTEX_FORMAT_SIZE; this.count++; //Goto next vertex
        this.meta(this.defaultMeta);
        float fx = (float) x, fy = (float) y, fz = (float) z;
        // bbox tracks PRE-offset (true block-local) coords for footprint measurement.
        if (fx < this.minX) this.minX = fx; if (fx > this.maxX) this.maxX = fx;
        if (fy < this.minY) this.minY = fy; if (fy > this.maxY) this.maxY = fy;
        if (fz < this.minZ) this.minZ = fz; if (fz > this.maxZ) this.maxZ = fz;
        MemoryUtil.memPutFloat(this.ptr, fx + this.offX);
        MemoryUtil.memPutFloat(this.ptr + 4, fy + this.offY);
        MemoryUtil.memPutFloat(this.ptr + 8, fz + this.offZ);
        return this;
    }

    public ReuseVertexConsumer meta(int metadata) {
        MemoryUtil.memPutInt(this.ptr + 12, metadata);
        return this;
    }

    @Override
    public VertexConsumer color(int red, int green, int blue, int alpha) {
        return this;
    }

    @Override
    public VertexConsumer uv(float u, float v) {
        MemoryUtil.memPutFloat(this.ptr + 16, u);
        MemoryUtil.memPutFloat(this.ptr + 20, v);
        return this;
    }

    @Override
    public VertexConsumer overlayCoords(int u, int v) {
        return this;
    }

    @Override
    public VertexConsumer uv2(int u, int v) {
        return this;
    }

    @Override
    public VertexConsumer normal(float x, float y, float z) {
        return this;
    }

    @Override
    public void endVertex() {
        // No-op; vertex data is written incrementally
    }

    @Override
    public void defaultColor(int r, int g, int b, int a) {
    }

    @Override
    public void unsetDefaultColor() {
    }

    public ReuseVertexConsumer quad(BakedQuad quad, int metadata) {
        this.anyShaded |= quad.isShade();
        // In 1.20.1 SpriteContents does not have mipmapStrategy field; skip darkened tex detection
        this.anyDarkendTex = false;
        this.ensureCanPut();
        int[] vertices = quad.getVertices();
        // Each vertex is 8 ints: posX, posY, posZ, color, u, v, light, normal
        for (int i = 0; i < 4; i++) {
            int offset = i * 8;
            float vx = Float.intBitsToFloat(vertices[offset]);
            float vy = Float.intBitsToFloat(vertices[offset + 1]);
            float vz = Float.intBitsToFloat(vertices[offset + 2]);
            float u = Float.intBitsToFloat(vertices[offset + 4]);
            float v = Float.intBitsToFloat(vertices[offset + 5]);
            this.vertex(vx, vy, vz);
            this.uv(u, v);
            this.meta(metadata);
        }
        return this;
    }

    private void ensureCanPut() {
        if ((long) (this.count + 5) * VERTEX_FORMAT_SIZE < this.buffer.size) {
            return;
        }
        long offset = this.ptr-this.buffer.address;
        //Double the size, rounded up to a VERTEX_FORMAT_SIZE multiple
        var newBuffer = new MemoryBuffer((((int)(this.buffer.size*2)+VERTEX_FORMAT_SIZE-1)/VERTEX_FORMAT_SIZE)*VERTEX_FORMAT_SIZE);
        this.buffer.cpyTo(newBuffer.address);
        this.buffer.free();
        this.buffer = newBuffer;
        this.ptr = offset + newBuffer.address;
    }

    public ReuseVertexConsumer reset() {
        this.anyShaded = false;
        this.anyDarkendTex = false;
        this.defaultMeta = 0;//RESET THE DEFAULT META
        this.count = 0;
        this.minX = this.minY = this.minZ = Float.POSITIVE_INFINITY;
        this.maxX = this.maxY = this.maxZ = Float.NEGATIVE_INFINITY;
        this.offX = this.offY = this.offZ = 0f;
        this.ptr = this.buffer.address - VERTEX_FORMAT_SIZE;//the thing is first time this gets incremented by FORMAT_STRIDE
        return this;
    }

    public void free() {
        this.ptr = 0;
        this.count = 0;
        this.buffer.free();
        this.buffer = null;
    }

    public boolean isEmpty() {
        return this.count == 0;
    }

    public int quadCount() {
        if (this.count%4 != 0) throw new IllegalStateException();
        return this.count/4;
    }

    public long getAddress() {
        return this.buffer.address;
    }

    // 64-bit hash of the currently-captured vertex bytes ([address, count*STRIDE)).
    // Used by the determinism gate to compare two BER captures cheaply without
    // snapshotting the buffer. Collision risk is negligible and the gate's failure
    // mode is bounded (a false "independent" verdict, fixable per-block).
    public long contentHash() {
        long h = 1125899906842597L;//large prime seed
        long bytes = (long) this.count * VERTEX_FORMAT_SIZE;//STRIDE is a multiple of 4
        long p = this.buffer.address;
        long end = p + bytes;
        while (p < end) {
            h = h * 31 + (MemoryUtil.memGetInt(p) & 0xFFFFFFFFL);
            p += 4;
        }
        return h ^ ((long) this.count * 0x9E3779B97F4A7C15L);
    }
}
