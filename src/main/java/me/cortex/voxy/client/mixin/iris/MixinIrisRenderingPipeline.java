package me.cortex.voxy.client.mixin.iris;

import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.client.core.util.IrisUtil;
import me.cortex.voxy.client.iris.IGetIrisVoxyPipelineData;
import me.cortex.voxy.client.iris.IGetVoxyPatchData;
import me.cortex.voxy.client.iris.IrisShaderPatch;
import me.cortex.voxy.client.iris.IrisVoxyRenderPipelineData;
import net.coderbot.iris.gl.buffer.ShaderStorageBufferHolder;
import net.coderbot.iris.pipeline.newshader.NewWorldRenderingPipeline;
import net.coderbot.iris.shaderpack.ProgramSet;
import net.coderbot.iris.uniforms.custom.CustomUniforms;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = NewWorldRenderingPipeline.class, remap = false)
public class MixinIrisRenderingPipeline implements IGetVoxyPatchData, IGetIrisVoxyPipelineData {
    @Shadow @Final private CustomUniforms customUniforms;
    @Shadow private ShaderStorageBufferHolder shaderStorageBufferHolder;

    @Unique private IrisShaderPatch voxy$patchData;
    @Unique private IrisVoxyRenderPipelineData voxy$pipelineData;

    @Inject(method = "<init>", at = @At("HEAD"))
    private void voxy$capturePatch(ProgramSet programSet, CallbackInfo callbackInfo) {
        if (IrisUtil.SHADER_SUPPORT) {
            this.voxy$patchData = ((IGetVoxyPatchData) programSet).voxy$getPatchData();
        }
    }

    @Inject(method = "<init>", at = @At("RETURN"))
    private void voxy$buildPipelineData(ProgramSet programSet, CallbackInfo callbackInfo) {
        if (this.voxy$patchData != null) {
            this.voxy$pipelineData = IrisVoxyRenderPipelineData.buildPipeline(
                    (NewWorldRenderingPipeline) (Object) this,
                    this.voxy$patchData,
                    this.customUniforms,
                    this.shaderStorageBufferHolder);
        }
    }

    @Inject(method = "destroy", at = @At("HEAD"))
    private void voxy$destroyPipeline(CallbackInfo callbackInfo) {
        if (this.voxy$pipelineData == null) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.levelRenderer instanceof IGetVoxyRenderSystem rendererAccess) {
            var renderer = rendererAccess.voxy$getRenderSystem();
            if (renderer != null && renderer.isUsingPipelineData(this.voxy$pipelineData)) {
                rendererAccess.voxy$shutdownRenderer();
            }
        }
        this.voxy$pipelineData = null;
    }

    @Inject(method = "beginLevelRendering", at = @At("HEAD"))
    private void voxy$applyCapturedViewport(CallbackInfo callbackInfo) {
        var captured = IrisUtil.CAPTURED_VIEWPORT_PARAMETERS;
        if (captured == null) {
            return;
        }

        var rendererAccess = (IGetVoxyRenderSystem) Minecraft.getInstance().levelRenderer;
        var renderer = rendererAccess.voxy$getRenderSystem();
        if (renderer == null && this.voxy$pipelineData != null) {
            rendererAccess.voxy$createRenderer();
            renderer = rendererAccess.voxy$getRenderSystem();
        }
        if (renderer != null) {
            captured.apply(renderer);
        }
    }

    @Override
    public IrisShaderPatch voxy$getPatchData() {
        return this.voxy$patchData;
    }

    @Override
    public IrisVoxyRenderPipelineData voxy$getPipelineData() {
        return this.voxy$pipelineData;
    }
}
