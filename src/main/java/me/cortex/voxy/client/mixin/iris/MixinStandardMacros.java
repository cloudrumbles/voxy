package me.cortex.voxy.client.mixin.iris;

import com.google.common.collect.ImmutableList;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.util.IrisUtil;
import me.cortex.voxy.client.iris.IrisShaderPatch;
import net.coderbot.iris.gl.shader.StandardMacros;
import net.coderbot.iris.shaderpack.StringPair;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = StandardMacros.class, remap = false)
public class MixinStandardMacros {
    @Inject(method = "createStandardEnvironmentDefines", at = @At("RETURN"), cancellable = true)
    private static void voxy$defineProtocol(
            CallbackInfoReturnable<Iterable<StringPair>> callbackInfo) {
        if (!VoxyConfig.CONFIG.isRenderingEnabled() || !IrisUtil.SHADER_SUPPORT) {
            return;
        }
        ImmutableList.Builder<StringPair> defines = ImmutableList.builder();
        defines.addAll(callbackInfo.getReturnValue());
        defines.add(new StringPair("VOXY", Integer.toString(IrisShaderPatch.SHADER_DEFINE_VERSION)));
        callbackInfo.setReturnValue(defines.build());
    }
}
