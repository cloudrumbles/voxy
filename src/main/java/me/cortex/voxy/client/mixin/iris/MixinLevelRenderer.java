package me.cortex.voxy.client.mixin.iris;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Matrix4f;
import me.cortex.voxy.client.core.util.IrisUtil;
import me.jellysquid.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import org.lwjgl.system.MemoryStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.FloatBuffer;

import static org.lwjgl.opengl.GL11C.glViewport;

@Mixin(LevelRenderer.class)
public class MixinLevelRenderer {
    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void voxy$captureShaderViewport(
            PoseStack matrices,
            float tickDelta,
            long finishTimeNano,
            boolean renderBlockOutline,
            Camera camera,
            GameRenderer gameRenderer,
            LightTexture lightTexture,
            Matrix4f projectionMatrix,
            CallbackInfo callbackInfo) {
        if (!IrisUtil.irisShaderPackEnabled()) {
            IrisUtil.CAPTURED_VIEWPORT_PARAMETERS = null;
            return;
        }

        var target = Minecraft.getInstance().getMainRenderTarget();
        glViewport(0, 0, target.width, target.height);
        var position = camera.getPosition();
        IrisUtil.CAPTURED_VIEWPORT_PARAMETERS = new IrisUtil.CapturedViewportParameters(
                new ChunkRenderMatrices(
                        voxy$toJoml(projectionMatrix),
                        voxy$toJoml(matrices.last().pose())),
                position.x,
                position.y,
                position.z);
    }

    @Unique
    private static org.joml.Matrix4f voxy$toJoml(Matrix4f source) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer values = stack.mallocFloat(16);
            source.store(values);
            return new org.joml.Matrix4f(values);
        }
    }
}
