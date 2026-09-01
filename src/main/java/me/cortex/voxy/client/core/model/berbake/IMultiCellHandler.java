package me.cortex.voxy.client.core.model.berbake;

import net.minecraft.world.level.block.state.BlockState;

/**
 * Marks a block whose block-entity geometry may span more than its own cell (e.g. a
 * Create water wheel) and should be expanded into neighbouring cells at LOD via the
 * synthetic-slice mechanism ({@link MultiCellSliceRegistry}).
 *
 * Deliberately minimal: the actual footprint is DERIVED from the block's captured
 * geometry bounds ({@link MultiCellFootprint}), not declared here, so handlers do not
 * hardcode dimensions. A handler only declares (a) that the block class is eligible and
 * (b) a safety cap on how far the expansion may reach.
 *
 * Registered against a Block class in {@link VoxyMultiCellRegistry}. Resolution is
 * BlockState-only (no BlockEntity/position access) so it is valid at mesh time, where the
 * source chunk's block entities are not available. Orientation/extent that distinguishes
 * footprints (e.g. a wheel's axis) must therefore come from the BlockState — verified true
 * for Create water wheels (AXIS/FACING are blockstate properties).
 */
public interface IMultiCellHandler {
    /**
     * Whether this specific state should be expanded. Lets a handler registered on a
     * broad block class opt individual states out (default: all states of the class).
     */
    default boolean shouldExpand(BlockState state) {
        return true;
    }

    /**
     * Hard cap on footprint reach per axis, in cells. Bounds both the synthetic-id count
     * and the stamping work for a single block. Small by construction (a wheel is ~1-2
     * cells of overhang); a generous default still prevents a pathological model from
     * ballooning the footprint.
     */
    default int maxFootprintRadius() {
        return 2;
    }
}
