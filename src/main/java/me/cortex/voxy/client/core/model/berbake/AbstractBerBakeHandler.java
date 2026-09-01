package me.cortex.voxy.client.core.model.berbake;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Generic BER-bake plumbing: build the block entity from the block's own
 * {@link EntityBlock#newBlockEntity}, and render it through the live
 * {@link net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher} — i.e.
 * the exact same renderer instance the game uses, no reimplementation. Subclasses only
 * declare which atlas the geometry samples.
 */
public abstract class AbstractBerBakeHandler implements IBerBakeHandler {
    @Override
    public BlockEntity createBlockEntity(BlockPos pos, BlockState state, Level level) {
        if (!(state.getBlock() instanceof EntityBlock entityBlock)) {
            return null;
        }
        BlockEntity be = entityBlock.newBlockEntity(pos, state);
        if (be != null) {
            be.setLevel(level);
        }
        return be;
    }

    @Override
    public void render(BlockEntity be, PoseStack pose, MultiBufferSource buffers, int packedLight, int packedOverlay) {
        var dispatcher = Minecraft.getInstance().getBlockEntityRenderDispatcher();
        renderTyped(dispatcher.getRenderer(be), be, pose, buffers, packedLight, packedOverlay);
    }

    // Localises the unchecked cast: getRenderer(be) returns BlockEntityRenderer<E> for
    // the BE's concrete type E, so feeding the same be back in is type-safe at runtime.
    @SuppressWarnings("unchecked")
    private static <E extends BlockEntity> void renderTyped(BlockEntityRenderer<E> renderer, BlockEntity be,
                                                            PoseStack pose, MultiBufferSource buffers,
                                                            int packedLight, int packedOverlay) {
        if (renderer == null) {
            return;
        }
        renderer.render((E) be, 0.0F, pose, buffers, packedLight, packedOverlay);
    }
}
