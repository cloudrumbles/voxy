package me.cortex.voxy.client.core.model.berbake;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Bakes a block-entity block's LOD by running its real BlockEntityRenderer into the
 * model bakery's vertex consumer, instead of falling back to the particle cube.
 *
 * Only valid for block entities whose rendered geometry is fully determined by their
 * BlockState (no neighbour / instance dependence) — chests are the first vehicle. The
 * determinism gate (render twice with differing neighbour configs) decides eligibility
 * for less obvious cases (Create cogs/waterwheels) later.
 *
 * Implementations must be pure with respect to (pos, world) beyond the synthetic
 * {@link me.cortex.voxy.client.core.model.berbake.BakeLevel} they are handed, so the
 * bake can be cached per BlockState at palette level.
 */
public interface IBerBakeHandler {
    /** Construct a throwaway BlockEntity carrying {@code state} at {@code pos}. */
    BlockEntity createBlockEntity(BlockPos pos, BlockState state, Level level);

    /** Render the block entity into {@code buffers} (which routes to the bakery vc). */
    void render(BlockEntity be, PoseStack pose, MultiBufferSource buffers, int packedLight, int packedOverlay);

    /**
     * The single texture atlas this block entity's geometry samples (e.g. the chest
     * sheet). Multi-atlas block entities are out of scope until the grouping pass.
     */
    ResourceLocation atlas();

    /**
     * True if this handler's geometry is known to be a pure function of the BlockState
     * (no neighbour dependence), letting the bakery skip the determinism gate entirely.
     * Chests qualify (geometry from FACING + TYPE). Default false: unknown handlers are
     * gated empirically, and only bake if they pass.
     */
    default boolean isKnownStateDetermined() {
        return false;
    }

    /**
     * Optional constant tint colour (0xAARRGGBB) to multiply over the captured geometry,
     * or -1 for none. For block entities whose colour is NOT in their texture but is a
     * state-derived vertex tint that {@link me.cortex.voxy.client.core.model.bakery.ReuseVertexConsumer}
     * discards (banners: a white cloth tinted by DyeColor). The bakery routes this through
     * its existing constant-tint slot (shader multiplies the sampled texture by it), so a
     * white-captured banner cloth comes out the right colour. Default -1: capture as-is.
     */
    default int constantTint(BlockState state) {
        return -1;
    }
}
