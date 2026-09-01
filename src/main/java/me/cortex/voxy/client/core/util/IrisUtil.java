package me.cortex.voxy.client.core.util;

import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.commonImpl.ForgePlatform;
import me.jellysquid.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.api.v0.IrisApi;
import net.irisshaders.iris.gl.IrisRenderSystem;
import net.irisshaders.iris.shadows.ShadowRenderer;

import java.io.IOException;

public final class IrisUtil {
    public record CapturedViewportParameters(ChunkRenderMatrices matrices, double x, double y, double z) {
        public Viewport<?> apply(VoxyRenderSystem renderSystem) {
            return renderSystem.setupViewport(this.matrices, this.x, this.y, this.z);
        }
    }

    public static CapturedViewportParameters CAPTURED_VIEWPORT_PARAMETERS;
    public static final boolean IRIS_INSTALLED = ForgePlatform.isModLoaded("iris")
            || ForgePlatform.isModLoaded("oculus");
    public static final boolean SHADER_SUPPORT = true;

    private IrisUtil() {
    }

    public static boolean irisShadowActive() {
        return IRIS_INSTALLED && ShadowRenderer.ACTIVE;
    }

    public static void clearIrisSamplers() {
        if (!IRIS_INSTALLED) {
            return;
        }
        for (int unit = 0; unit < 16; unit++) {
            IrisRenderSystem.bindSamplerToUnit(unit, 0);
        }
    }

    public static void reload() {
        if (!IRIS_INSTALLED) {
            return;
        }
        try {
            if (IrisApi.getInstance().isShaderPackInUse()
                    || IrisApi.getInstance().getConfig().areShadersEnabled()) {
                Iris.reload();
            }
        } catch (IOException exception) {
            throw new RuntimeException(exception);
        }
    }

    public static boolean irisShaderPackEnabled() {
        return IRIS_INSTALLED && Iris.isPackInUseQuick();
    }

    public static boolean irisShadersEnabledInConfig() {
        return IRIS_INSTALLED && !Iris.getCurrentPack().isEmpty();
    }

    public static void disableIrisShaders() {
        if (IRIS_INSTALLED) {
            IrisApi.getInstance().getConfig().setShadersEnabledAndApply(false);
        }
    }
}
