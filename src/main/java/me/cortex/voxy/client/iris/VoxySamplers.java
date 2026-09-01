package me.cortex.voxy.client.iris;

import net.irisshaders.iris.gl.sampler.GlSampler;
import net.irisshaders.iris.gl.sampler.SamplerHolder;
import net.irisshaders.iris.gl.texture.TextureType;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;

public class VoxySamplers {
    // Equivalent of GlSampler.MIPPED_NEAREST_NEAREST from Iris 1.21.x
    private static final GlSampler MIPPED_NEAREST_NEAREST = new GlSampler(false, true, false, false);
    // Bilinear, clamp-to-edge. The terrain heightmap is a continuous field
    // sampled by a marching shader; nearest sampling makes the marched result
    // jump a whole 16 m texel at a time as the light sweeps, which reads as the
    // shadow stepping in discrete increments. Filtering is free in hardware and
    // R32F is filterable on desktop GL. Consumers that need the exact stored
    // value (the no-data sentinel test) still use texelFetch, which ignores this.
    private static final GlSampler LINEAR_LINEAR = new GlSampler(true, false, false, false);

    public static void addSamplers(IrisRenderingPipeline pipeline, SamplerHolder samplers) {
        var patchData = ((IGetVoxyPatchData)pipeline).voxy$getPatchData();
        if (patchData != null) {
            String[] opaqueNames = new String[]{"vxDepthTexOpaque"};
            String[] translucentNames = new String[]{"vxDepthTexTrans"};

            //TODO replace ()->0 with the actual depth texture id
            samplers.addDynamicSampler(TextureType.TEXTURE_2D, () -> {
                var pipeData = ((IGetIrisVoxyPipelineData)pipeline).voxy$getPipelineData();
                if (pipeData == null) {
                    return 0;
                }
                if (pipeData.thePipeline == null) {
                    return 0;
                }

                //In theory the first frame could be null
                var dt = pipeData.thePipeline.fb.getDepthTex();
                if (dt == null) {
                    return 0;
                }
                return dt.id;
            }, MIPPED_NEAREST_NEAREST, opaqueNames);

            samplers.addDynamicSampler(TextureType.TEXTURE_2D, () -> {
                var pipeData = ((IGetIrisVoxyPipelineData)pipeline).voxy$getPipelineData();
                if (pipeData == null) {
                    return 0;
                }
                if (pipeData.thePipeline == null) {
                    return 0;
                }
                //In theory the first frame could be null
                var dt = pipeData.thePipeline.fbTranslucent.getDepthTex();
                if (dt == null) {
                    return 0;
                }
                return dt.id;
            }, MIPPED_NEAREST_NEAREST, translucentNames);

            // LOD terrain heightmap (horizon-silhouette shadows). R32F:
            // texel = height in blocks of the highest terrain in a 16x16
            // block column; -10000 = no LOD data (non-occluding). Window
            // placement comes from the vxHeightmap* uniforms (VoxyUniforms).
            samplers.addDynamicSampler(TextureType.TEXTURE_2D, () -> {
                var getVrs = (me.cortex.voxy.client.core.IGetVoxyRenderSystem) net.minecraft.client.Minecraft.getInstance().levelRenderer;
                if (getVrs == null || getVrs.getVoxyRenderSystem() == null) {
                    return 0;
                }
                var heightmap = getVrs.getVoxyRenderSystem().getTerrainHeightmap();
                return heightmap == null ? 0 : heightmap.getTextureId();
            }, LINEAR_LINEAR, new String[]{"vxTerrainHeightmap"});
        }
    }
}
