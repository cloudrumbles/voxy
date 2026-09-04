package me.cortex.voxy.client.iris;

import net.coderbot.iris.gl.sampler.GlSampler;
import net.coderbot.iris.gl.sampler.SamplerHolder;
import net.coderbot.iris.gl.texture.TextureType;
import net.coderbot.iris.pipeline.newshader.NewWorldRenderingPipeline;

public final class VoxySamplers {
    private static final GlSampler DEPTH_SAMPLER = new GlSampler(false, false, false, false);

    private VoxySamplers() {
    }

    public static void addSamplers(NewWorldRenderingPipeline pipeline, SamplerHolder samplers) {
        IrisShaderPatch patch = ((IGetVoxyPatchData) pipeline).voxy$getPatchData();
        if (patch == null) {
            return;
        }

        samplers.addDynamicSampler(
                TextureType.TEXTURE_2D,
                () -> depthTexture(pipeline, false),
                DEPTH_SAMPLER,
                "vxDepthTexOpaque");
        samplers.addDynamicSampler(
                TextureType.TEXTURE_2D,
                () -> depthTexture(pipeline, true),
                DEPTH_SAMPLER,
                "vxDepthTexTrans");
    }

    private static int depthTexture(NewWorldRenderingPipeline pipeline, boolean translucent) {
        IrisVoxyRenderPipelineData data = ((IGetIrisVoxyPipelineData) pipeline).voxy$getPipelineData();
        if (data == null || data.thePipeline == null) {
            return 0;
        }
        var framebuffer = translucent ? data.thePipeline.fbTranslucent : data.thePipeline.fb;
        var depth = framebuffer.getDepthTex();
        return depth == null ? 0 : depth.id;
    }
}
