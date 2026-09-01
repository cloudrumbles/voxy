package me.cortex.voxy.client.core.rendering;

import com.mojang.blaze3d.systems.RenderSystem;

/**
 * Replacement for Sodium 0.8.x's FogParameters which doesn't exist in Embeddium 0.3.x.
 * Captures fog state from RenderSystem at the time of construction.
 */
public record FogParameters(float environmentalStart, float environmentalEnd, float red, float green, float blue, float alpha) {
    public static FogParameters capture() {
        float[] color = RenderSystem.getShaderFogColor();
        return new FogParameters(
                RenderSystem.getShaderFogStart(),
                RenderSystem.getShaderFogEnd(),
                color[0], color[1], color[2], color[3]
        );
    }
}
