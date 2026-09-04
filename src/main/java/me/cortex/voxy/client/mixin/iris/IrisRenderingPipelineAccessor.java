package me.cortex.voxy.client.mixin.iris;

import com.google.common.collect.ImmutableSet;
import net.coderbot.iris.pipeline.newshader.NewWorldRenderingPipeline;
import net.coderbot.iris.rendertarget.RenderTargets;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = NewWorldRenderingPipeline.class, remap = false)
public interface IrisRenderingPipelineAccessor {
    @Accessor("renderTargets")
    RenderTargets voxy$getRenderTargets();

    @Accessor("flippedAfterPrepare")
    ImmutableSet<Integer> voxy$getFlippedAfterPrepare();
}
