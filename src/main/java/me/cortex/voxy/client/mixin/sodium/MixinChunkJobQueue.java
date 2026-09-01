package me.cortex.voxy.client.mixin.sodium;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.concurrent.Semaphore;

@Mixin(targets={"me.jellysquid.mods.sodium.client.render.chunk.compile.executor.ChunkJobQueue"},remap = false)
public class MixinChunkJobQueue {
    // Formerly swapped in a SemaphoreBlockImpersonator backed by voxy's
    // MultiThreadPrioritySemaphore to share worker threads with Sodium.
    // On Forge 1.20.1 that integration deadlocks the Sodium chunk builder
    // (workers block in acquire() forever, no meshes produced, nothing
    // renders). The upstream implementation even carries a self-comment
    // admitting it "very much probably doesnt" function correctly. Until
    // the unified pool is reworked, always return a plain Semaphore.
    @Redirect(method = "<init>", at = @At(value = "NEW", target = "(I)Ljava/util/concurrent/Semaphore;"))
    private Semaphore voxy$injectUnifiedPool(int permits) {
        return new Semaphore(permits);
    }
}
