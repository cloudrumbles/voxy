package me.cortex.voxy.common.compat;

import net.minecraft.world.level.block.state.BlockState;

@FunctionalInterface
public interface IStateProxyResolver {
    /**
     * Map an ingested BlockState to a different BlockState before voxy stores it.
     * Identity by default. Implementations must be pure (state-only) — no position
     * or BlockEntity access — so that voxy can apply this at palette level rather
     * than per voxel.
     */
    BlockState resolve(BlockState state);
}
