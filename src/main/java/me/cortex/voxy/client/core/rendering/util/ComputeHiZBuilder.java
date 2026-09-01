package me.cortex.voxy.client.core.rendering.util;

import me.cortex.voxy.client.core.gl.GlTexture;
import me.cortex.voxy.client.core.gl.shader.Shader;
import me.cortex.voxy.client.core.gl.shader.ShaderType;

import static org.lwjgl.opengl.ARBDirectStateAccess.glBindTextureUnit;
import static org.lwjgl.opengl.ARBDirectStateAccess.glTextureParameteri;
import static org.lwjgl.opengl.ARBShaderImageLoadStore.GL_TEXTURE_FETCH_BARRIER_BIT;
import static org.lwjgl.opengl.GL11C.*;
import static org.lwjgl.opengl.GL12C.GL_CLAMP_TO_EDGE;
import static org.lwjgl.opengl.GL12C.GL_TEXTURE_WRAP_S;
import static org.lwjgl.opengl.GL12C.GL_TEXTURE_WRAP_T;
import static org.lwjgl.opengl.GL14C.GL_TEXTURE_COMPARE_MODE;
import static org.lwjgl.opengl.GL20C.glUniform1i;
import static org.lwjgl.opengl.GL20C.glUniform2i;
import static org.lwjgl.opengl.GL30C.GL_R32F;
import static org.lwjgl.opengl.GL33C.*;
import static org.lwjgl.opengl.GL42C.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT;
import static org.lwjgl.opengl.GL42C.GL_WRITE_ONLY;
import static org.lwjgl.opengl.GL42C.glBindImageTexture;
import static org.lwjgl.opengl.GL42C.glMemoryBarrier;
import static org.lwjgl.opengl.GL43C.glDispatchCompute;

// Builds the hierarchical-Z depth pyramid the GPU traversal culls against:
// base = highestOneBit(width) x highestOneBit(height) with ceil(log2(max
// dim)) levels, each texel storing the FARTHEST depth (max) of the 2x2 it
// covers — always conservative for occlusion. A 2x2 footprint containing a
// far-plane sample reduces to the far plane (the "any-sky patch"), so
// geometry edges against sky never occlude. After buildMipChain returns the
// pyramid is safe to sample without further barriers.
//
// Construction is 1 init dispatch + ceil((levels-1)/5) chain dispatches
// (shared-memory reduction tree, no subgroup-extension dependencies). This
// replaced the original fragment-pass builder (~levels sequential fullscreen
// passes, each fenced by glTextureBarrier + glMemoryBarrier with a
// framebuffer re-attachment per level); the two were verified bit-identical
// across every level in-game via a since-removed compare harness
// (HiZCompareHarness, deleted alongside the fragment builder — resurrect
// from git history if a second implementation ever needs validating).
public class ComputeHiZBuilder {
    // Levels a single chain dispatch can produce (shared-memory tree depth).
    private static final int CHAIN_LEVELS = 5;

    private final Shader init = Shader.make()
            .add(ShaderType.COMPUTE, "voxy:hiz/downsample_init.comp")
            .compile()
            .name("HiZ compute init");
    private final Shader chain = Shader.make()
            .add(ShaderType.COMPUTE, "voxy:hiz/downsample_chain.comp")
            .compile()
            .name("HiZ compute chain");

    private final int sampler = glGenSamplers();
    private GlTexture texture;
    private int levels;
    private int width;
    private int height;

    public ComputeHiZBuilder() {
        // Matches the fragment builder's source sampler: gather respects
        // wrap mode, and CLAMP_TO_EDGE at the source border is part of the
        // value contract.
        glSamplerParameteri(this.sampler, GL_TEXTURE_MIN_FILTER, GL_NEAREST_MIPMAP_NEAREST);
        glSamplerParameteri(this.sampler, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glSamplerParameteri(this.sampler, GL_TEXTURE_COMPARE_MODE, GL_NONE);
        glSamplerParameteri(this.sampler, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glSamplerParameteri(this.sampler, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    }

    private void alloc(int width, int height) {
        this.levels = (int) Math.ceil(Math.log(Math.max(width, height)) / Math.log(2));
        // R32F, not a depth format: compute image stores cannot target depth
        // textures. The traversal samples it as a plain float texture either
        // way (texelFetch(...).r).
        this.texture = new GlTexture().store(GL_R32F, this.levels, width, height).name("HiZ (compute)");
        glTextureParameteri(this.texture.id, GL_TEXTURE_MIN_FILTER, GL_NEAREST_MIPMAP_NEAREST);
        glTextureParameteri(this.texture.id, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTextureParameteri(this.texture.id, GL_TEXTURE_COMPARE_MODE, GL_NONE);
        glTextureParameteri(this.texture.id, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTextureParameteri(this.texture.id, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        this.width = width;
        this.height = height;
    }


    public void buildMipChain(int srcDepthTex, int width, int height) {
        if (this.width != Integer.highestOneBit(width) || this.height != Integer.highestOneBit(height)) {
            if (this.texture != null) {
                this.texture.free();
                this.texture = null;
            }
            this.alloc(Integer.highestOneBit(width), Integer.highestOneBit(height));
        }

        // Level 0: gather-downsample the (possibly non-pow2) source depth.
        this.init.bind();
        glBindTextureUnit(0, srcDepthTex);
        glBindSampler(0, this.sampler);
        glBindImageTexture(1, this.texture.id, 0, false, 0, GL_WRITE_ONLY, GL_R32F);
        glUniform2i(0, this.width, this.height);
        glDispatchCompute((this.width + 15) / 16, (this.height + 15) / 16, 1);
        // The chain dispatch texelFetches mip 0 written above.
        glMemoryBarrier(GL_TEXTURE_FETCH_BARRIER_BIT | GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);

        // Levels 1..levels-1, up to CHAIN_LEVELS per dispatch.
        this.chain.bind();
        glBindTextureUnit(0, this.texture.id);
        glBindSampler(0, this.sampler);
        int src = 0;
        while (src < this.levels - 1) {
            int out = Math.min(CHAIN_LEVELS, this.levels - 1 - src);
            for (int i = 1; i <= CHAIN_LEVELS; i++) {
                // Bind unused image units to the last real level; the shader
                // guards stores with outLevels so they are never written.
                int mip = Math.min(src + i, this.levels - 1);
                glBindImageTexture(i, this.texture.id, mip, false, 0, GL_WRITE_ONLY, GL_R32F);
            }
            int srcW = Math.max(this.width >> src, 1);
            int srcH = Math.max(this.height >> src, 1);
            glUniform1i(0, src);
            glUniform1i(1, out);
            glUniform2i(2, srcW, srcH);
            glDispatchCompute((srcW + 31) / 32, (srcH + 31) / 32, 1);
            // Next chain iteration (and ultimately the traversal) samples
            // the mips written by this dispatch.
            glMemoryBarrier(GL_TEXTURE_FETCH_BARRIER_BIT | GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);
            src += out;
        }

        glBindSampler(0, 0);
        glBindTextureUnit(0, 0);
    }


    public int getHizTextureId() {
        return this.texture.id;
    }


    public int getPackedLevels() {
        // Pixel dimensions of the pyramid base — screenspace.glsl's mip
        // selection consumes these as sizes.
        return (this.width << 16) | this.height;
    }


    public void free() {
        if (this.texture != null) {
            this.texture.free();
            this.texture = null;
        }
        glDeleteSamplers(this.sampler);
        this.init.free();
        this.chain.free();
    }
}
