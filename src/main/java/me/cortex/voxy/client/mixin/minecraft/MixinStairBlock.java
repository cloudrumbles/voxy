package me.cortex.voxy.client.mixin.minecraft;

import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

// Forge 1.20.1 ships a coremod (field_to_method.js) that rewrites
// StairBlock.baseState; widening the field to public via accesstransformer
// trips its "Field f_56859_ is not private and an instance field" check
// and aborts Bootstrap. A mixin accessor leaves the field private while
// still giving voxy a typed reader.
@Mixin(StairBlock.class)
public interface MixinStairBlock {
    @Accessor("baseState")
    BlockState voxy$getBaseState();
}
