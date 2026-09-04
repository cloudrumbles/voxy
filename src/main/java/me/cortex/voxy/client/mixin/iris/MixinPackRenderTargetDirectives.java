package me.cortex.voxy.client.mixin.iris;

import com.google.common.collect.ImmutableSet;
import net.coderbot.iris.shaderpack.PackRenderTargetDirectives;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(value = PackRenderTargetDirectives.class, remap = false)
public class MixinPackRenderTargetDirectives {
    @Redirect(
            method = "<clinit>",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/google/common/collect/ImmutableSet$Builder;build()Lcom/google/common/collect/ImmutableSet;"))
    private static ImmutableSet<Integer> voxy$extendRenderTargets(
            ImmutableSet.Builder<Integer> builder) {
        int limit = Boolean.getBoolean("voxy.IrisExtremeColourTexOverride") ? 200 : 20;
        for (int index = 16; index < limit; index++) {
            builder.add(index);
        }
        return builder.build();
    }
}
