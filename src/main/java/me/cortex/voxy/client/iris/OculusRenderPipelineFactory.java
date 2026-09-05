package me.cortex.voxy.client.iris;

import me.cortex.voxy.client.core.AbstractRenderPipeline;
import me.cortex.voxy.client.core.IrisVoxyRenderPipeline;
import me.cortex.voxy.client.core.rendering.hierachical.AsyncNodeManager;
import me.cortex.voxy.client.core.rendering.hierachical.HierarchicalOcclusionTraverser;
import me.cortex.voxy.client.core.rendering.hierachical.NodeCleaner;
import net.coderbot.iris.Iris;
import net.coderbot.iris.pipeline.newshader.NewWorldRenderingPipeline;

import java.util.function.BooleanSupplier;

/** Creates Voxy's shader-aware pipeline from the active Oculus world pipeline. */
public final class OculusRenderPipelineFactory {
    private OculusRenderPipelineFactory() {
    }

    public static AbstractRenderPipeline create(
            AsyncNodeManager nodeManager,
            NodeCleaner nodeCleaner,
            HierarchicalOcclusionTraverser traversal,
            BooleanSupplier frexSupplier) {
        var pipeline = Iris.getPipelineManager().getPipelineNullable();
        if (!(pipeline instanceof NewWorldRenderingPipeline)) {
            throw new ShaderLoadError("Oculus reports shaders enabled without an active shader pipeline");
        }
        if (!(pipeline instanceof IGetIrisVoxyPipelineData dataAccess)) {
            throw new ShaderLoadError("The active Oculus pipeline does not expose Voxy bridge data");
        }

        IrisVoxyRenderPipelineData data = dataAccess.voxy$getPipelineData();
        if (data == null) {
            throw new ShaderLoadError("The selected shader pack does not provide a Voxy shader program");
        }

        return new IrisVoxyRenderPipeline(data, nodeManager, nodeCleaner, traversal, frexSupplier);
    }
}
