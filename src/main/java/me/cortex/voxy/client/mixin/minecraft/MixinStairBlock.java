package me.cortex.voxy.client.mixin.minecraft;

import me.cortex.voxy.client.core.util.StairBlockCooked;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(StairBlock.class)
public class MixinStairBlock implements StairBlockCooked {
    @Shadow
    @Final
    private BlockState baseState;

    @Override
    public BlockState setBaseWaterlogged(BlockState blockState) {
        if (this.baseState.hasProperty(BlockStateProperties.WATERLOGGED)) {
            return this.baseState.setValue(
                    BlockStateProperties.WATERLOGGED,
                    blockState.getValue(BlockStateProperties.WATERLOGGED)
            );
        }
        return this.baseState;
    }
}
