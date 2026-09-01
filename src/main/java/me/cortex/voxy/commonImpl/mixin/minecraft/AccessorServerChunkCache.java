package me.cortex.voxy.commonImpl.mixin.minecraft;

import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.ServerChunkCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ServerChunkCache.class)
public interface AccessorServerChunkCache {
    @Accessor("distanceManager")
    DistanceManager voxy$getDistanceManager();
}
