package me.cortex.voxy.client.core.util;

import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.jellysquid.mods.sodium.client.render.chunk.ChunkRenderMatrices;

/**
 * Compatibility boundary for shader integrations.
 *
 * The 1.19.2 Oculus line uses an older Iris implementation API. Until its
 * adapter is loaded, these operations deliberately report the normal renderer
 * path and otherwise do nothing. Keeping the boundary here prevents optional
 * shader classes from becoming hard dependencies of the core renderer.
 */
public final class IrisUtil {
    public record CapturedViewportParameters(ChunkRenderMatrices matrices, double x, double y, double z) {
        public Viewport<?> apply(VoxyRenderSystem renderSystem) {
            return renderSystem.setupViewport(this.matrices, this.x, this.y, this.z);
        }
    }

    public static CapturedViewportParameters CAPTURED_VIEWPORT_PARAMETERS;
    public static final boolean IRIS_INSTALLED = false;
    public static final boolean SHADER_SUPPORT = false;

    private IrisUtil() {
    }

    public static boolean irisShadowActive() {
        return false;
    }

    public static void clearIrisSamplers() {
    }

    public static void reload() {
    }

    public static boolean irisShaderPackEnabled() {
        return false;
    }

    public static boolean irisShadersEnabledInConfig() {
        return false;
    }

    public static void disableIrisShaders() {
    }
}
