package me.cortex.voxy.client.mixin.iris;

import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.iris.IGetVoxyPatchData;
import me.cortex.voxy.client.iris.IrisShaderPatch;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.include.AbsolutePackPath;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.shaderpack.properties.PackDirectives;
import net.irisshaders.iris.shaderpack.properties.ShaderProperties;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Function;

@Mixin(value = ProgramSet.class, remap = false)
public class MixinProgramSet implements IGetVoxyPatchData {
    @Shadow @Final private PackDirectives packDirectives;
    @Unique IrisShaderPatch patchData;

    @Inject(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/irisshaders/iris/shaderpack/programs/ProgramSet;locateDirectives()V", shift = At.Shift.BEFORE))
    private void voxy$injectPatchMaker(AbsolutePackPath directory, Function<AbsolutePackPath, String> sourceProvider, ShaderProperties shaderProperties, ShaderPack pack, CallbackInfo ci) {
        // Gate on user intent (isRenderingConfigured), not factory availability,
        // so the patch is built even if iris's ProgramSet is constructed before
        // VoxyClient.initVoxyClient() has registered the voxy factory. Without
        // this, voxy's section-renderer shader compile fails with "undefined
        // variable vxDepthTexOpaque/vxProjInv" because the patch data is null.
        if (VoxyConfig.CONFIG.isRenderingConfigured()) {
            this.patchData = IrisShaderPatch.makePatch(pack, directory, sourceProvider);
        }
        /*
        if (this.patchData != null) {
            //Inject directives from voxy
            DispatchingDirectiveHolder ddh = new DispatchingDirectiveHolder();
            this.packDirectives.acceptDirectivesFrom(ddh);
            CommentDirectiveParser.findDirective(this.patchData.getPatchSource(), CommentDirective.Type.RENDERTARGETS)
                    .map(dir->Arrays.stream(dir.getDirective().split(","))
                            .mapToInt(Integer::parseInt).toArray())
                    .ifPresent(ddh::processDirective);

        }
         */
    }


    @Override
    public IrisShaderPatch voxy$getPatchData() {
        return this.patchData;
    }
}
