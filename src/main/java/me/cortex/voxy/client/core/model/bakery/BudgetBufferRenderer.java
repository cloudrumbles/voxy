package me.cortex.voxy.client.core.model.bakery;

import com.mojang.blaze3d.vertex.VertexFormat;
import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.gl.GlVertexArray;
import me.cortex.voxy.client.core.gl.shader.Shader;
import me.cortex.voxy.client.core.gl.shader.ShaderType;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

import static org.lwjgl.opengl.GL20.glUniformMatrix4fv;
import static org.lwjgl.opengl.GL33.glBindSampler;
import static org.lwjgl.opengl.GL45.*;

public class BudgetBufferRenderer {
    public static final int VERTEX_FORMAT_SIZE = 24;

    private static final Shader bakeryShader = Shader.make()
            .add(ShaderType.VERTEX, "voxy:bakery/position_tex.vsh")
            .add(ShaderType.FRAGMENT, "voxy:bakery/position_tex.fsh")
            .compile();


    public static void init(){}
    private static final GlBuffer indexBuffer;
    static {
        // Generate quad index buffer directly instead of copying from Minecraft's internal buffer
        int quadCount = 4096;
        int indexCount = quadCount * 6;
        int byteSize = indexCount * 2; // SHORT = 2 bytes
        // GL_DYNAMIC_STORAGE_BIT is required for glNamedBufferSubData below to
        // succeed against immutable storage. Without it the driver emits
        // GL_INVALID_OPERATION (id=1282) and this buffer stays zero-filled,
        // which makes every LOD quad rendered via BudgetBufferRenderer
        // degenerate -> LOD geometry is invisible.
        indexBuffer = new GlBuffer(byteSize, GL_DYNAMIC_STORAGE_BIT);
        ByteBuffer buf = MemoryUtil.memAlloc(byteSize);
        for (int q = 0; q < quadCount; q++) {
            int base = q * 4;
            buf.putShort((short)(base));
            buf.putShort((short)(base + 1));
            buf.putShort((short)(base + 2));
            buf.putShort((short)(base + 2));
            buf.putShort((short)(base + 3));
            buf.putShort((short)(base));
        }
        buf.flip();
        glNamedBufferSubData(indexBuffer.id, 0, buf);
        MemoryUtil.memFree(buf);
    }

    private static final int STRIDE = 24;
    private static final GlVertexArray VA = new GlVertexArray()
            .setStride(STRIDE)
            .setF(0, GL_FLOAT, 4, 0)//pos, metadata
            .setF(1, GL_FLOAT, 2, 4 * 4)//UV
            .bindElementBuffer(indexBuffer.id);

    private static GlBuffer immediateBuffer;
    private static int quadCount;

    public static void setup(long dataPtr, int quads, int texId) {
        if (quads == 0) {
            throw new IllegalStateException();
        }

        quadCount = quads;

        long size = quads * 4L * STRIDE;
        if (immediateBuffer == null || immediateBuffer.size()<size) {
            if (immediateBuffer != null) {
                immediateBuffer.free();
            }
            immediateBuffer = new GlBuffer(size*2L);//This also accounts for when immediateBuffer == null
            VA.bindBuffer(immediateBuffer.id);
        }
        long ptr = UploadStream.INSTANCE.upload(immediateBuffer, 0, size);
        MemoryUtil.memCopy(dataPtr, ptr, size);
        UploadStream.INSTANCE.commit();

        bakeryShader.bind();
        VA.bind();
        glMemoryBarrier(GL_VERTEX_ATTRIB_ARRAY_BARRIER_BIT);
        glBindSampler(0, 0);
        glBindTextureUnit(0, texId);
    }

    public static void render(Matrix4f matrix) {
        glUniformMatrix4fv(1, false, matrix.get(new float[16]));
        glDrawElements(GL_TRIANGLES, quadCount * 2 * 3, GL_UNSIGNED_SHORT, 0);
    }
}
