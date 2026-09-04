package me.cortex.voxy.client.mixin.minecraft;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Minecraft.class)
public interface AccessorMinecraft {
    @Accessor("fps")
    static int voxy$getFps() {
        throw new AssertionError();
    }
}
