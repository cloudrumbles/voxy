package me.cortex.voxy.client.core;

import me.cortex.voxy.client.core.rendering.hierachical.AsyncNodeManager;
import me.cortex.voxy.client.core.rendering.hierachical.HierarchicalOcclusionTraverser;
import me.cortex.voxy.client.core.rendering.hierachical.NodeCleaner;
import me.cortex.voxy.client.core.util.IrisUtil;
import me.cortex.voxy.client.iris.OculusRenderPipelineFactory;

import java.util.function.BooleanSupplier;

public final class RenderPipelineFactory {
    private RenderPipelineFactory() {
    }

    public static AbstractRenderPipeline createPipeline(
            AsyncNodeManager nodeManager,
            NodeCleaner nodeCleaner,
            HierarchicalOcclusionTraverser traversal,
            BooleanSupplier frexSupplier) {
        if (IrisUtil.irisShaderPackEnabled()) {
            return OculusRenderPipelineFactory.create(
                    nodeManager, nodeCleaner, traversal, frexSupplier);
        }
        return new NormalRenderPipeline(nodeManager, nodeCleaner, traversal, frexSupplier);
    }
}
