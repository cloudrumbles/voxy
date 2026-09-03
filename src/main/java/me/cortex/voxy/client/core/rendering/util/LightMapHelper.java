package me.cortex.voxy.client.core.rendering.util;

import me.cortex.voxy.client.mixin.minecraft.AccessorLightTexture;
import static org.lwjgl.opengl.GL33.glBindSampler;
import static org.lwjgl.opengl.GL45.glBindTextureUnit;

import net.minecraft.client.Minecraft;

public class LightMapHelper {
    public static void bind(int lightingIndex) {
        glBindSampler(lightingIndex, 0);
        glBindTextureUnit(lightingIndex, getLightmapTextureId());
    }

    public static int getLightmapTextureId() {
        return ((AccessorLightTexture) Minecraft.getInstance().gameRenderer.lightTexture()).voxy$getLightTexture().getId();
    }
}