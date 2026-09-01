package me.cortex.voxy.client.core.model.berbake;

import net.minecraft.client.renderer.Sheets;
import net.minecraft.resources.ResourceLocation;

/**
 * BER-bake handler for chests (vanilla + any mod chest whose block extends
 * AbstractChestBlock and renders via the chest sheet). All chest variants — normal,
 * trapped, ender, single/left/right — share the single {@link Sheets#CHEST_SHEET}
 * atlas, so one atlas binding covers every state.
 *
 * Geometry (facing, single/double half) is fully determined by the BlockState: the
 * renderer selects the half-model from the {@code TYPE} property and applies the
 * {@code FACING} rotation itself, so no neighbour context is needed for the shape.
 *
 * The Christmas texture variant is accepted as-is (constant within a session, visually
 * irrelevant for this project); no special handling.
 */
public final class ChestBerBakeHandler extends AbstractBerBakeHandler {
    @Override
    public ResourceLocation atlas() {
        return Sheets.CHEST_SHEET;
    }

    @Override
    public boolean isKnownStateDetermined() {
        return true;//FACING + TYPE fully determine geometry; skip the determinism gate
    }
}
