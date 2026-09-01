package me.cortex.voxy.commonImpl.mixin.minecraft;

import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.util.thread.BlockableEventLoop;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ChunkMap.class)
public interface InvokerChunkMap {
    @Invoker("getUpdatingChunkIfPresent")
    @Nullable
    ChunkHolder voxy$getUpdatingChunkIfPresent(long chunkPosKey);

    // chunkMap.mainThreadExecutor is a ServerChunkCache.MainThreadExecutor —
    // a BlockableEventLoop<Runnable> that does NOT override scheduleExecutables(),
    // so its execute() always queues via tell(). Safe to submit chunk-system
    // work from any thread without the inline-execute race that hits
    // MinecraftServer.execute() during managedBlock recursion.
    @Accessor("mainThreadExecutor")
    BlockableEventLoop<Runnable> voxy$getMainThreadExecutor();
}
