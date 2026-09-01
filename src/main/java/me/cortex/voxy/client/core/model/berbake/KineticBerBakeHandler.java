package me.cortex.voxy.client.core.model.berbake;

import net.minecraft.resources.ResourceLocation;

/**
 * BER-bake handler for Create kinetic blocks whose geometry is drawn by a
 * BlockEntityRenderer onto the BLOCK atlas (water wheels, cogwheels). The /voxyprobe
 * diagnostic confirmed these capture real geometry through the VertexConsumer on
 * minecraft:textures/atlas/blocks.png (cutout_mipped / solid), e.g. water_wheel q=1276,
 * large_water_wheel q=2136, cogwheel q=42, large_cogwheel q=92, none throwing.
 *
 * Unlike chests (which sample a dedicated CHEST_SHEET), these sample the block atlas, so
 * the handler simply returns it.
 *
 * isKnownStateDetermined() is left at the default (false), so the bakery's determinism
 * gate runs: it captures the geometry twice under differing synthetic neighbours and only
 * trusts the bake if the output is identical (i.e. neighbour-independent). Water-wheel and
 * cog geometry is determined by their BlockState (AXIS/FACING), so the gate should pass;
 * if a given state turns out neighbour-dependent it falls back to the existing cube path.
 *
 * NOTE (single-cell, this milestone): the capture is baked into the block's OWN cell and
 * clipped at the cell edge, exactly like any other block model. Overhang beyond the cell
 * (the large wheel's rim, the large cog's teeth) is not yet represented across neighbour
 * cells — that is the later multi-cell stamping work. This handler is the immediately
 * testable, lower-risk step that reuses the shipped chest BER-bake path verbatim.
 */
public final class KineticBerBakeHandler extends AbstractBerBakeHandler {
    private static final ResourceLocation BLOCK_ATLAS =
            new ResourceLocation("minecraft", "textures/atlas/blocks.png");

    @Override
    public ResourceLocation atlas() {
        return BLOCK_ATLAS;
    }
}
