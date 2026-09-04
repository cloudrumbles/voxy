package me.cortex.voxy.client.mixin.iris;

import com.google.common.collect.ImmutableSet;
import me.cortex.voxy.client.iris.VoxySamplers;
import net.coderbot.iris.gbuffer_overrides.matching.InputAvailability;
import net.coderbot.iris.gl.image.ImageHolder;
import net.coderbot.iris.gl.sampler.SamplerHolder;
import net.coderbot.iris.pipeline.newshader.NewWorldRenderingPipeline;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Supplier;

@Mixin(value = NewWorldRenderingPipeline.class, remap = false)
public class MixinIrisSamplers {
    @Inject(method = "addGbufferOrShadowSamplers", at = @At("TAIL"))
    private void voxy$addDepthSamplers(
            SamplerHolder samplers,
            ImageHolder images,
            Supplier<ImmutableSet<Integer>> flipped,
            boolean shadowPass,
            InputAvailability availability,
            CallbackInfo callbackInfo) {
        VoxySamplers.addSamplers((NewWorldRenderingPipeline) (Object) this, samplers);
    }
}
