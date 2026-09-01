package me.cortex.voxy.client.iris;

import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import net.irisshaders.iris.gl.uniform.UniformHolder;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;

import java.util.function.Supplier;

import static net.irisshaders.iris.gl.uniform.UniformUpdateFrequency.PER_FRAME;

public class VoxyUniforms {

    public static Matrix4f getViewProjection() {//This is 1 frame late ;-; cries, since the update occurs _before_ the voxy render pipeline
        var getVrs = (IGetVoxyRenderSystem) Minecraft.getInstance().levelRenderer;
        if (getVrs == null || getVrs.getVoxyRenderSystem() == null) {
            return new Matrix4f();
        }
        var vrs = getVrs.getVoxyRenderSystem();
        return new Matrix4f(vrs.getViewport().MVP);
    }

    public static Matrix4f getModelView() {//This is 1 frame late ;-; cries, since the update occurs _before_ the voxy render pipeline
        var getVrs = (IGetVoxyRenderSystem) Minecraft.getInstance().levelRenderer;
        if (getVrs == null || getVrs.getVoxyRenderSystem() == null) {
            return new Matrix4f();
        }
        var vrs = getVrs.getVoxyRenderSystem();
        return new Matrix4f(vrs.getViewport().modelView);
    }

    public static Matrix4f getProjection() {//This is 1 frame late ;-; cries, since the update occurs _before_ the voxy render pipeline
        var getVrs = (IGetVoxyRenderSystem) Minecraft.getInstance().levelRenderer;
        if (getVrs == null || getVrs.getVoxyRenderSystem() == null) {
            return new Matrix4f();
        }
        var vrs = getVrs.getVoxyRenderSystem();
        var mat = vrs.getViewport().projection;
        if (mat == null) {
            return new Matrix4f();
        }
        return new Matrix4f(mat);
    }

    private static me.cortex.voxy.client.core.rendering.heightmap.TerrainHeightmapTracker getHeightmap() {
        var getVrs = (IGetVoxyRenderSystem) Minecraft.getInstance().levelRenderer;
        if (getVrs == null || getVrs.getVoxyRenderSystem() == null) {
            return null;
        }
        return getVrs.getVoxyRenderSystem().getTerrainHeightmap();
    }

    // Copied rather than handed out directly: the tracker mutates its stored
    // origins in place as rings upload, and a uniform holder is entitled to keep
    // the reference it is given.
    private static org.joml.Vector2i ringOrigin(int ring) {
        var hm = getHeightmap();
        return hm == null ? new org.joml.Vector2i() : new org.joml.Vector2i(hm.getRingOrigin(ring));
    }

    public static void addUniforms(UniformHolder uniforms) {
        uniforms
                // LOD terrain heightmap clipmap (see VoxySamplers). Rings are
                // stacked in one texture, each SideTexels square; ring i has
                // texel (0,0) at Origin<i> in world blocks and a texel size of
                // 2^(i+1) blocks, so it spans SideTexels << (i+1). SideTexels
                // == 0 means the clipmap is not fully resident yet.
                .uniform2i(PER_FRAME, "vxHeightmapOrigin0", () -> ringOrigin(0))
                .uniform2i(PER_FRAME, "vxHeightmapOrigin1", () -> ringOrigin(1))
                .uniform2i(PER_FRAME, "vxHeightmapOrigin2", () -> ringOrigin(2))
                .uniform2i(PER_FRAME, "vxHeightmapOrigin3", () -> ringOrigin(3))
                .uniform1i(PER_FRAME, "vxHeightmapSideTexels", () -> {
                    var hm = getHeightmap();
                    return hm == null ? 0 : hm.getSideTexels();
                })
                .uniform1i(PER_FRAME, "vxHeightmapRings",
                        () -> me.cortex.voxy.client.core.rendering.heightmap.TerrainHeightmapBuilder.RINGS)
                .uniform1i(PER_FRAME, "vxRenderDistance", ()->Math.round(VoxyConfig.CONFIG.sectionRenderDistance*32))//In chunks
                .uniformMatrix(PER_FRAME, "vxViewProj", VoxyUniforms::getViewProjection)
                .uniformMatrix(PER_FRAME, "vxViewProjInv", new Inverted(VoxyUniforms::getViewProjection))
                .uniformMatrix(PER_FRAME, "vxViewProjPrev", new PreviousMat(VoxyUniforms::getViewProjection))
                .uniformMatrix(PER_FRAME, "vxModelView", VoxyUniforms::getModelView)
                .uniformMatrix(PER_FRAME, "vxModelViewInv", new Inverted(VoxyUniforms::getModelView))
                .uniformMatrix(PER_FRAME, "vxModelViewPrev", new PreviousMat(VoxyUniforms::getModelView))
                .uniformMatrix(PER_FRAME, "vxProj", VoxyUniforms::getProjection)
                .uniformMatrix(PER_FRAME, "vxProjInv", new Inverted(VoxyUniforms::getProjection))
                .uniformMatrix(PER_FRAME, "vxProjPrev", new PreviousMat(VoxyUniforms::getProjection));
    }




    private record Inverted(Supplier<Matrix4f> parent) implements Supplier<Matrix4f> {
        private Inverted(Supplier<Matrix4f> parent) {
            this.parent = parent;
        }

        public Matrix4f get() {
            Matrix4f copy = new Matrix4f(this.parent.get());
            copy.invert();
            return copy;
        }

        public Supplier<Matrix4f> parent() {
            return this.parent;
        }
    }

    private static class PreviousMat implements Supplier<Matrix4f> {
        private final Supplier<Matrix4f> parent;
        private Matrix4f previous;

        PreviousMat(Supplier<Matrix4f> parent) {
            this.parent = parent;
            this.previous = new Matrix4f();
        }

        public Matrix4f get() {
            Matrix4f previous = this.previous;
            this.previous = new Matrix4f(this.parent.get());
            return previous;
        }
    }
}
