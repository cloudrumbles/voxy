package me.cortex.voxy.common.distantgen;

import com.mojang.datafixers.util.Either;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.commonImpl.mixin.minecraft.AccessorServerChunkCache;
import me.cortex.voxy.commonImpl.mixin.minecraft.InvokerChunkMap;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;
import java.util.function.LongConsumer;

// Owns the lifecycle of a single in-flight distant-gen chunk request. Submits
// the request on the MC main thread (chunkMap.mainThreadExecutor), hooks
// completion via whenCompleteAsync, hands the resulting LevelChunk to the
// save service, then releases the ticket back on the main thread once
// voxelisation is done.
//
// Threading contract:
//   - request()/release() callable from ANY thread; we marshal onto the
//     server's main-thread executor internally.
//   - whenCompleteAsync handler runs on ForkJoinPool.commonPool() — chosen
//     instead of the completing thread so we never hold a vanilla chunk-
//     system lock while dispatching. The handler is intentionally light
//     (deque add + permit release), so commonPool latency is irrelevant.
public class VoxyDistantGenTicketManager {
    private final ServerLevel level;
    private final WorldEngine engine;
    private final VoxyDistantGenSaveService saveService;
    // Notified with the chunk key (ChunkPos.toLong()) AFTER the save service
    // has finished voxelising and inserting a generated chunk. Used by
    // VoxyDistantGen to track per-chunk completion for the walker's frontier
    // logic. May be a no-op (e.g. in tests).
    private final LongConsumer onChunkCompleted;

    // In-flight requests by ChunkPos.toLong(). Updated on request submit + on
    // future-resolution; checked by the scheduler for backpressure and to
    // prevent duplicate submissions.
    private final ConcurrentHashMap<Long, CompletableFuture<Void>> inFlight = new ConcurrentHashMap<>();

    // Diagnostic counters — incremented at each future-resolution step.
    public final java.util.concurrent.atomic.AtomicLong cntRequestSucceeded = new java.util.concurrent.atomic.AtomicLong();
    public final java.util.concurrent.atomic.AtomicLong cntRequestFailed = new java.util.concurrent.atomic.AtomicLong();

    public VoxyDistantGenTicketManager(ServerLevel level, WorldEngine engine,
                                       VoxyDistantGenSaveService saveService,
                                       LongConsumer onChunkCompleted) {
        this.level = level;
        this.engine = engine;
        this.saveService = saveService;
        this.onChunkCompleted = onChunkCompleted;
    }

    public int inFlightCount() {
        return this.inFlight.size();
    }

    // Logs (does not throw) when a chunk-system mutation site runs on a
    // thread other than the server tick thread. Should never fire in normal
    // operation — every mutator is wrapped in chunkMapExecutor.execute,
    // which the chunk map runs from the server thread via pollTask. If it
    // does fire, it points at a mod-introduced violation of the executor's
    // contract (e.g. some mixin redirecting pollTask onto another thread)
    // and we'd need to re-evaluate our threading assumptions.
    private void assertOnServerThread(String site) {
        var server = this.level.getServer();
        if (server != null && Thread.currentThread() != server.getRunningThread()) {
            Logger.error("voxy distant-gen chunk-system mutation OFF SERVER THREAD at " + site
                    + ", thread=" + Thread.currentThread().getName(), new Throwable("trace"));
        }
    }

    public boolean isPending(ChunkPos pos) {
        return this.inFlight.containsKey(pos.toLong());
    }

    // Request a chunk to be generated to FULL status. Returns a future that
    // completes (with null) when voxelisation has finished and the ticket has
    // been released. Same-chunk requests are deduplicated — a second call
    // while one is in-flight returns the existing future.
    public CompletableFuture<Void> request(ChunkPos chunkPos) {
        long key = chunkPos.toLong();
        var existing = this.inFlight.get(key);
        if (existing != null) {
            return existing;
        }

        var completion = new CompletableFuture<Void>();
        var prev = this.inFlight.putIfAbsent(key, completion);
        if (prev != null) {
            return prev;
        }

        var chunkSource = this.level.getChunkSource();
        var distanceManager = ((AccessorServerChunkCache) chunkSource).voxy$getDistanceManager();
        var chunkMap = chunkSource.chunkMap;
        var chunkMapExecutor = ((InvokerChunkMap) chunkMap).voxy$getMainThreadExecutor();

        if (me.cortex.voxy.client.config.VoxyConfig.CONFIG.distantGenVerboseLogging) {
            Logger.info("Distant-gen ticket-mgr: request (" + chunkPos.x + "," + chunkPos.z + ") queued onto chunkMapExecutor");
        }

        // Add ticket + schedule gen on the chunk map's main-thread executor.
        // Using chunkMap.mainThreadExecutor (a ServerChunkCache.MainThreadExecutor)
        // instead of server.execute is critical: server.execute can run the
        // task INLINE on the calling thread when ReentrantBlockableEventLoop's
        // reentrantCount is non-zero (during vanilla's managedBlock chunk-load
        // recursion), causing distance-manager mutations to race with the
        // main thread. The chunk map's executor doesn't override
        // scheduleExecutables(), so its execute() always queues via tell().
        chunkMapExecutor.execute(() -> {
            try {
                // Defensive: confirm we're actually on the server thread. The
                // chunk map's main-thread executor is supposed to run tasks
                // there, but if some mod's mixin into the executor breaks
                // that contract we'd be mutating chunk-system state off-
                // thread (the c2me-au-naturel 2026-05-24 race analysis
                // identified this as the trigger class for the
                // PersistentEntitySectionManager AIOOBE).
                assertOnServerThread("addTicket@" + chunkPos);
                distanceManager.addTicket(
                        VoxyDistantGenTickets.TICKET, chunkPos, VoxyDistantGenTickets.TICKET_LEVEL, chunkPos);
                // Force the ticket through immediately — without this the chunk
                // may not advance to FULL until the next chunk-system tick.
                distanceManager.runAllUpdates(chunkMap);

                if (me.cortex.voxy.client.config.VoxyConfig.CONFIG.distantGenVerboseLogging) {
                    Logger.info("Distant-gen ticket-mgr: ticket added (" + chunkPos.x + "," + chunkPos.z + "), scheduling FULL future");
                }

                ChunkHolder holder = ((InvokerChunkMap) chunkMap).voxy$getUpdatingChunkIfPresent(key);
                if (holder == null) {
                    // Should not happen after adding a ticket; treat as a
                    // transient failure and release.
                    finishWithoutChunk(chunkPos, completion,
                            new IllegalStateException("No chunk holder after ticket add at " + chunkPos));
                    return;
                }

                CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>> genFuture =
                        holder.getOrScheduleFuture(ChunkStatus.FULL, chunkMap);

                // Use commonPool — handler is intentionally light (deque add +
                // permit release), and we don't want to hold a vanilla chunk-
                // system lock during dispatch.
                genFuture.whenCompleteAsync(
                        (either, err) -> onGenComplete(chunkPos, completion, either, err),
                        ForkJoinPool.commonPool());
            } catch (Throwable t) {
                finishWithoutChunk(chunkPos, completion, t);
            }
        });

        return completion;
    }

    private void onGenComplete(ChunkPos chunkPos,
                               CompletableFuture<Void> completion,
                               Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure> either,
                               Throwable err) {
        boolean verbose = me.cortex.voxy.client.config.VoxyConfig.CONFIG.distantGenVerboseLogging;
        if (err != null) {
            this.cntRequestFailed.incrementAndGet();
            if (verbose) Logger.info("Distant-gen ticket-mgr: future FAILED (" + chunkPos.x + "," + chunkPos.z + ") err=" + err.getClass().getSimpleName() + ": " + err.getMessage());
            finishWithoutChunk(chunkPos, completion, err);
            return;
        }
        if (either == null) {
            this.cntRequestFailed.incrementAndGet();
            if (verbose) Logger.info("Distant-gen ticket-mgr: future resolved with NULL Either (" + chunkPos.x + "," + chunkPos.z + ")");
            finishWithoutChunk(chunkPos, completion, null);
            return;
        }

        var maybeChunk = either.left();
        if (maybeChunk.isEmpty()) {
            this.cntRequestFailed.incrementAndGet();
            if (verbose) Logger.info("Distant-gen ticket-mgr: vanilla load-failure (" + chunkPos.x + "," + chunkPos.z + ") right=" + either.right().map(Object::toString).orElse("(no detail)"));
            finishWithoutChunk(chunkPos, completion, null);
            return;
        }

        var chunkAccess = maybeChunk.get();
        if (!(chunkAccess instanceof LevelChunk levelChunk)) {
            this.cntRequestFailed.incrementAndGet();
            if (verbose) Logger.info("Distant-gen ticket-mgr: chunk NOT LevelChunk (" + chunkPos.x + "," + chunkPos.z + ") actual=" + chunkAccess.getClass().getName());
            finishWithoutChunk(chunkPos, completion, null);
            return;
        }

        if (!this.engine.isLive()) {
            this.cntRequestFailed.incrementAndGet();
            if (verbose) Logger.info("Distant-gen ticket-mgr: engine NOT live, abandoning (" + chunkPos.x + "," + chunkPos.z + ")");
            finishWithoutChunk(chunkPos, completion, null);
            return;
        }

        this.cntRequestSucceeded.incrementAndGet();
        if (verbose) Logger.info("Distant-gen ticket-mgr: future SUCCEEDED (" + chunkPos.x + "," + chunkPos.z + ") -> enqueuing saveService");

        // Hand off to save service. The release callback fires once
        // voxelisation completes (or aborts).
        this.saveService.enqueue(this.engine, levelChunk, chunkPos.toLong(), key -> {
            if (verbose) Logger.info("Distant-gen ticket-mgr: saveService onDone (" + chunkPos.x + "," + chunkPos.z + ") -> releasing ticket + marking completed");
            releaseTicketAndComplete(chunkPos, key, () -> {
                try {
                    this.onChunkCompleted.accept(key);
                } catch (Throwable t) {
                    Logger.error("Distant-gen onChunkCompleted callback failed at " + chunkPos + ": " + t.getMessage(), t);
                }
                completion.complete(null);
            });
        });
    }

    private void finishWithoutChunk(ChunkPos chunkPos, CompletableFuture<Void> completion, Throwable err) {
        if (err != null) {
            Logger.error("Distant-gen request failed at " + chunkPos + ": " + err.getMessage(), err);
        }
        releaseTicketAndComplete(chunkPos, chunkPos.toLong(), () -> completion.complete(null));
    }

    // Release on chunkMap's main-thread executor (same executor we added the
    // ticket on, for the same inline-execute-safety reasons documented above).
    // The inFlight removal + afterRelease callback run INSIDE this executor
    // task (in finally so they always fire) so the scheduler can't observe
    // inFlight as cleared until the ticket is actually gone. Without this
    // sequencing, scheduler resubmission queues an addTicket AFTER the
    // pending removeTicket, causing FIFO-ordered remove/add cycles that
    // briefly unload then reload the chunk.
    private void releaseTicketAndComplete(ChunkPos chunkPos, long key, Runnable afterRelease) {
        var chunkSource = this.level.getChunkSource();
        var distanceManager = ((AccessorServerChunkCache) chunkSource).voxy$getDistanceManager();
        var chunkMapExecutor = ((InvokerChunkMap) chunkSource.chunkMap).voxy$getMainThreadExecutor();
        chunkMapExecutor.execute(() -> {
            try {
                assertOnServerThread("removeTicket@" + chunkPos);
                distanceManager.removeTicket(
                        VoxyDistantGenTickets.TICKET, chunkPos, VoxyDistantGenTickets.TICKET_LEVEL, chunkPos);
            } catch (Throwable t) {
                Logger.error("Distant-gen ticket release failed at " + chunkPos + ": " + t.getMessage(), t);
            } finally {
                this.inFlight.remove(key);
                try {
                    afterRelease.run();
                } catch (Throwable t) {
                    Logger.error("Distant-gen post-release callback failed at " + chunkPos + ": " + t.getMessage(), t);
                }
            }
        });
    }

    // Called on shutdown — cancel in-flight futures. Deliberately does NOT
    // call releaseTicket for each one: vanilla's level-unload destroys the
    // entire chunk system and its ticket store, so any tickets we leave
    // behind vanish with it. Bursting N removeTicket calls onto the chunk
    // map executor at shutdown queued chunk-system mutations that c2me's
    // mid_tick_chunk_tasks then ran while vanilla was iterating
    // PersistentEntitySectionManager — exactly the race that caused the
    // AIOOBE crash in 2026-05-24_09.26.21-server.txt. Cancelling the
    // futures (and letting vanilla clean up tickets) avoids the burst.
    public void shutdown() {
        var snapshot = new java.util.ArrayList<Long>(this.inFlight.keySet());
        for (long key : snapshot) {
            var fut = this.inFlight.remove(key);
            if (fut != null && !fut.isDone()) {
                fut.completeExceptionally(new java.util.concurrent.CancellationException("distant-gen shutdown"));
            }
        }
    }
}
