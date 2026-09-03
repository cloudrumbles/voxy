package me.cortex.voxy.client.core;

import me.cortex.voxy.client.core.rendering.hierachical.AsyncNodeManager;
import me.cortex.voxy.client.core.rendering.hierachical.HierarchicalOcclusionTraverser;
import me.cortex.voxy.client.core.rendering.hierachical.NodeCleaner;

import java.util.function.BooleanSupplier;

/**
 * Creates the renderer pipeline for the Forge 1.19.2 port.
 *
 * Oculus 1.19.2 exposes the pre-rename Iris API and cannot host Voxy's newer
 * shader pipeline without a dedicated adapter. The normal pipeline remains the
 * authoritative renderer and preserves all non-shader Voxy behaviour.
 */
public final class RenderPipelineFactory {
    private RenderPipelineFactory() {
    }

    public static AbstractRenderPipeline createPipeline(
            AsyncNodeManager nodeManager,
            NodeCleaner nodeCleaner,
            HierarchicalOcclusionTraverser traversal,
            BooleanSupplier frexSupplier) {
        return new NormalRenderPipeline(nodeManager, nodeCleaner, traversal, frexSupplier);
    }
}
