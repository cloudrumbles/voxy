package me.cortex.voxy.client.iris;

import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import net.coderbot.iris.gl.uniform.UniformHolder;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

import java.util.function.Supplier;

import static net.coderbot.iris.gl.uniform.UniformUpdateFrequency.PER_FRAME;

public final class VoxyUniforms {
    private VoxyUniforms() {
    }

    private static Matrix4f viewProjection() {
        var renderer = renderer();
        return renderer == null ? new Matrix4f() : new Matrix4f(renderer.getViewport().MVP);
    }

    private static Matrix4f modelView() {
        var renderer = renderer();
        return renderer == null ? new Matrix4f() : new Matrix4f(renderer.getViewport().modelView);
    }

    private static Matrix4f projection() {
        var renderer = renderer();
        if (renderer == null || renderer.getViewport().projection == null) {
            return new Matrix4f();
        }
        return new Matrix4f(renderer.getViewport().projection);
    }

    private static me.cortex.voxy.client.core.VoxyRenderSystem renderer() {
        var levelRenderer = Minecraft.getInstance().levelRenderer;
        if (!(levelRenderer instanceof IGetVoxyRenderSystem access)) {
            return null;
        }
        return access.voxy$getRenderSystem();
    }

    public static void addUniforms(UniformHolder uniforms) {
        uniforms
                .uniform1i(PER_FRAME, "vxRenderDistance",
                        () -> Math.round(VoxyConfig.CONFIG.sectionRenderDistance * 32))
                .uniformMatrixFromArray(PER_FRAME, "vxViewProj", matrix(VoxyUniforms::viewProjection))
                .uniformMatrixFromArray(PER_FRAME, "vxViewProjInv", inverted(VoxyUniforms::viewProjection))
                .uniformMatrixFromArray(PER_FRAME, "vxViewProjPrev", previous(VoxyUniforms::viewProjection))
                .uniformMatrixFromArray(PER_FRAME, "vxModelView", matrix(VoxyUniforms::modelView))
                .uniformMatrixFromArray(PER_FRAME, "vxModelViewInv", inverted(VoxyUniforms::modelView))
                .uniformMatrixFromArray(PER_FRAME, "vxModelViewPrev", previous(VoxyUniforms::modelView))
                .uniformMatrixFromArray(PER_FRAME, "vxProj", matrix(VoxyUniforms::projection))
                .uniformMatrixFromArray(PER_FRAME, "vxProjInv", inverted(VoxyUniforms::projection))
                .uniformMatrixFromArray(PER_FRAME, "vxProjPrev", previous(VoxyUniforms::projection));
    }

    private static Supplier<float[]> matrix(Supplier<? extends Matrix4fc> source) {
        return () -> toArray(source.get());
    }

    private static Supplier<float[]> inverted(Supplier<? extends Matrix4fc> source) {
        return () -> toArray(new Matrix4f(source.get()).invert());
    }

    private static Supplier<float[]> previous(Supplier<? extends Matrix4fc> source) {
        return new Supplier<>() {
            private Matrix4f previous = new Matrix4f();

            @Override
            public float[] get() {
                Matrix4f result = this.previous;
                this.previous = new Matrix4f(source.get());
                return toArray(result);
            }
        };
    }

    private static float[] toArray(Matrix4fc matrix) {
        float[] values = new float[16];
        matrix.get(values);
        return values;
    }
}
