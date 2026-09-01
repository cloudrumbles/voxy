package me.cortex.voxy.common.distantgen;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.common.thread.ServiceManager;
import me.cortex.voxy.common.world.WorldEngine;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

// Per-(ServerLevel, WorldEngine) coordinator. Owns one TicketManager + one
// SaveService + one Scheduler and drives them on a periodic tick. There is
// one of these per dimension once Phase 2 wires multi-dimension; Phase 1 has
// overworld-only.
//
// Target position + look direction are refreshed each tick from the nearest
// player in the level. The driver runs on its own daemon thread (MIN_PRIORITY)
// at 10 Hz so we never starve the server tick or render thread.
public class VoxyDistantGen {
    private final ServerLevel level;
    private final WorldEngine engine;
    private final VoxyDistantGenSaveService saveService;
    private final VoxyDistantGenTicketManager ticketManager;
    private final VoxyDistantGenScheduler scheduler;

    private final ScheduledExecutorService driver =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "Voxy distant-gen driver");
                t.setDaemon(true);
                t.setPriority(Thread.MIN_PRIORITY);
                return t;
            });

    private volatile boolean shutdown = false;

    // Set by VoxyMod's ServerStoppingEvent listener — every coordinator
    // observes this and stops submitting new work the moment the server
    // begins shutdown. Without this, the walker keeps pushing chunk-system
    // tasks right up until LevelEvent.Unload, and that burst of queued
    // tasks is what amplifies c2me's mid-tick-task race against vanilla's
    // entity unload (see crash report 2026-05-24_09.26.21-server).
    public static volatile boolean serverStopping = false;

    // Walker state — concentric-ring scan from the anchor player outward to
    // distantGenRadius. The walker enforces strict 4-neighbour adjacency:
    // a chunk is only submitted when at least one of its N/S/E/W neighbours
    // is already vanilla-loaded or LOD'd in voxy storage. The frontier
    // therefore grows contiguously outward — never isolated chunks.
    //
    // Each tick scans up to WALKER_SCAN_BUDGET chunk positions and submits
    // up to WALKER_SUBMIT_BUDGET that pass the frontier test. The cursor
    // wraps around to ring 1 when it reaches the radius, so subsequent ticks
    // pick up newly-completed frontier chunks. Cursor resets to 1 when the
    // player moves more than WALKER_MOVE_RESET_THRESHOLD chunks.
    private static final int WALKER_SCAN_BUDGET = 5000;
    private static final int WALKER_SUBMIT_BUDGET = 128;
    private static final int WALKER_MOVE_RESET_THRESHOLD = 8;
    private static final int COMPLETED_SET_MAX = 200_000;
    // Heartbeat log every ~5s (driver ticks at 10Hz, so 50 ticks).
    private static final int HEARTBEAT_TICK_INTERVAL = 50;
    @Nullable private ChunkPos walkerAnchor = null;
    private int walkerRing = 1;
    private int heartbeatCounter = 0;
    // Rings proven fully processed this session (every position isProcessed
    // when last scanned). "Processed" is monotone within a session, so a
    // completed ring can never need scanning again until the anchor resets.
    // Without this, the steady-state walker rescans the whole disc forever:
    // up to WALKER_SCAN_BUDGET positions per 100 ms tick, each unprocessed
    // position paying a RocksDB exists-probe — pure waste once generation
    // has converged. With it, the cursor skips straight to frontier rings
    // and the walker idles entirely once all rings are done.
    private final java.util.BitSet completedRings = new java.util.BitSet();
    // Per-chunk record of chunks the SaveService has successfully voxelised
    // this session. The TicketManager calls markCompleted() from its
    // saveService completion callback. Used by the walker to (a) skip
    // already-done chunks, (b) treat them as completed terrain for the
    // frontier-adjacency check.
    //
    // Not populated from voxy's RocksDB on startup: voxy stores LOD data at
    // 2x2 chunk granularity per worldSection (one LOD-0 worldSection holds
    // 8 chunks of voxel data), so containsSection cannot tell us per-chunk
    // whether a specific chunk has been processed. Cost of not pre-populating:
    // walker re-processes already-LOD'd chunks once on session start, which
    // is cheap (vanilla returns them from region files immediately).
    // LongSets.synchronize wraps a LongOpenHashSet with a monitor for safe
    // concurrent driver-read + worker-write. Was ConcurrentHashMap<Long,Boolean>
    // — boxed key+value, ~12-13 MiB at the 200k cap. Primitive set is ~4.7 MiB
    // at cap and removes autoboxing on the hot containsKey/put path. Monitor
    // contention is acceptable here: walker (driver thread) reads are
    // microsecond-scale containsKey hits; writes from worker threads are
    // dozens-per-second at most (one per completed chunk).
    private final LongSet sessionCompleted = LongSets.synchronize(new LongOpenHashSet(1024));

    // Phase counters for diagnostics. Incremented at each step of the
    // chunk's journey. Reported (as deltas) in the heartbeat log so we can
    // see throughput and identify where chunks are stalling.
    public final java.util.concurrent.atomic.AtomicLong cntWalkerConsidered = new java.util.concurrent.atomic.AtomicLong();
    public final java.util.concurrent.atomic.AtomicLong cntWalkerSubmitted = new java.util.concurrent.atomic.AtomicLong();
    public final java.util.concurrent.atomic.AtomicLong cntMarkedCompleted = new java.util.concurrent.atomic.AtomicLong();
    private long lastHeartbeatConsidered, lastHeartbeatSubmitted, lastHeartbeatMarked;
    private long lastHeartbeatSchedDispatch, lastHeartbeatTicketSuccess, lastHeartbeatTicketFailed;
    private long lastHeartbeatVoxSuccess, lastHeartbeatVoxFailed;

    public VoxyDistantGen(ServerLevel level, WorldEngine engine, ServiceManager voxyServices) {
        this.level = level;
        this.engine = engine;
        this.saveService = new VoxyDistantGenSaveService(voxyServices);
        this.ticketManager = new VoxyDistantGenTicketManager(level, engine, this.saveService, this::markCompleted);
        this.scheduler = new VoxyDistantGenScheduler(this.ticketManager);
    }

    // Start the periodic driver. Updates target from nearest player and invokes
    // scheduler.tick(). Period intentionally coarse (10 Hz) — finer than this
    // doesn't help because vanilla's chunk pipeline batches submissions anyway.
    public void start() {
        this.driver.scheduleAtFixedRate(this::tickSafe, 100, 100, TimeUnit.MILLISECONDS);
    }

    private void tickSafe() {
        if (this.shutdown) return;
        try {
            tick();
        } catch (Throwable t) {
            // Driver thread must not die — log and continue.
            me.cortex.voxy.common.Logger.error("Distant-gen driver tick failed: " + t.getMessage(), t);
        }
    }

    private void tick() {
        if (this.shutdown || !this.engine.isLive()) return;

        // Stop submitting and dispatching as soon as the server begins to
        // shut down. Keeps the queued-task burst small so c2me's mid-tick
        // chunk-task processing has less opportunity to interleave with
        // vanilla's entity-section unload during the shutdown tick.
        if (serverStopping) return;

        Player nearest = pickAnchorPlayer();
        ChunkPos anchorChunk = null;
        if (nearest != null) {
            anchorChunk = new ChunkPos((int) nearest.getX() >> 4, (int) nearest.getZ() >> 4);
            this.scheduler.setTargetPos(anchorChunk);
            this.scheduler.setLookDirection(nearest.getLookAngle());
        }

        if (VoxyConfig.CONFIG.distantGenEnabled && anchorChunk != null) {
            this.scheduler.setMaxInFlight(VoxyConfig.CONFIG.distantGenThreads);
            walkTick(anchorChunk, VoxyConfig.CONFIG.distantGenRadius);
        }

        this.scheduler.tick();

        // Heartbeat: log current state every ~5s so progress can be inspected
        // without inferring from visuals. Reports both absolute totals and
        // deltas (Δ) since the previous heartbeat, so it's clear at a glance
        // which phase is making progress and which is stalled.
        if (++this.heartbeatCounter >= HEARTBEAT_TICK_INTERVAL) {
            this.heartbeatCounter = 0;
            long considered = this.cntWalkerConsidered.get();
            long submitted = this.cntWalkerSubmitted.get();
            long marked = this.cntMarkedCompleted.get();
            long dispatched = this.scheduler.cntDispatched.get();
            long ticketOk = this.ticketManager.cntRequestSucceeded.get();
            long ticketFail = this.ticketManager.cntRequestFailed.get();
            long voxOk = this.saveService.cntVoxeliseSucceeded.get();
            long voxFail = this.saveService.cntVoxeliseFailed.get();

            //Gate only the log line, not the delta bookkeeping below, so that
            //enabling heartbeatLogging mid-session reports a correct ~5s delta
            //rather than one catch-up window spanning the whole session.
            if (VoxyConfig.CONFIG.heartbeatLogging) {
                me.cortex.voxy.common.Logger.info(
                        "Distant-gen heartbeat dim=" + this.level.dimension().location()
                                + " anchor=" + (anchorChunk == null ? "(none)" : anchorChunk.x + "," + anchorChunk.z)
                                + " walkerRing=" + this.walkerRing
                                + " completedRings=" + this.completedRings.cardinality()
                                + " schedQueued=" + this.scheduler.waitingCount()
                                + " inFlight=" + this.ticketManager.inFlightCount()
                                + " saveQueue=" + this.saveService.queueDepth()
                                + " sessionCompleted=" + this.sessionCompleted.size()
                                + " | walkerConsidered=" + considered + "(Δ" + (considered - this.lastHeartbeatConsidered) + ")"
                                + " walkerSubmitted=" + submitted + "(Δ" + (submitted - this.lastHeartbeatSubmitted) + ")"
                                + " dispatched=" + dispatched + "(Δ" + (dispatched - this.lastHeartbeatSchedDispatch) + ")"
                                + " ticketOk=" + ticketOk + "(Δ" + (ticketOk - this.lastHeartbeatTicketSuccess) + ")"
                                + " ticketFail=" + ticketFail + "(Δ" + (ticketFail - this.lastHeartbeatTicketFailed) + ")"
                                + " voxOk=" + voxOk + "(Δ" + (voxOk - this.lastHeartbeatVoxSuccess) + ")"
                                + " voxFail=" + voxFail + "(Δ" + (voxFail - this.lastHeartbeatVoxFailed) + ")"
                                + " marked=" + marked + "(Δ" + (marked - this.lastHeartbeatMarked) + ")");
            }

            this.lastHeartbeatConsidered = considered;
            this.lastHeartbeatSubmitted = submitted;
            this.lastHeartbeatMarked = marked;
            this.lastHeartbeatSchedDispatch = dispatched;
            this.lastHeartbeatTicketSuccess = ticketOk;
            this.lastHeartbeatTicketFailed = ticketFail;
            this.lastHeartbeatVoxSuccess = voxOk;
            this.lastHeartbeatVoxFailed = voxFail;
        }
    }

    // Frontier walker. Scans concentric rings outward from the anchor and
    // submits chunks that are (a) not loaded by vanilla, (b) not already
    // LOD'd in voxy storage, (c) not already queued/in-flight, AND (d) have
    // at least one N/S/E/W neighbour that is loaded or LOD'd. The last
    // condition is the contiguous-expansion rule — no chunk is ever
    // submitted before something next to it exists.
    //
    // Persistent cursor (walkerRing) advances each tick within the per-tick
    // scan budget. When the cursor wraps past `radius`, it resets to 1 so
    // subsequent ticks rescan and pick up frontier growth.
    private void walkTick(ChunkPos anchor, int radius) {
        if (radius <= 0) return;

        if (this.walkerAnchor == null
                || Math.max(Math.abs(anchor.x - this.walkerAnchor.x),
                            Math.abs(anchor.z - this.walkerAnchor.z)) >= WALKER_MOVE_RESET_THRESHOLD) {
            this.walkerAnchor = anchor;
            this.walkerRing = 1;
            this.completedRings.clear();
        }

        int scanRemaining = WALKER_SCAN_BUDGET;
        int submitRemaining = WALKER_SUBMIT_BUDGET;

        // Loop multiple rings per tick, advancing walkerRing as each completes.
        // Returns early when either budget is exhausted, or immediately when
        // every ring within the radius is proven fully processed.
        while (scanRemaining > 0 && submitRemaining > 0) {
            if (this.walkerRing > radius) {
                this.walkerRing = 1;
            }
            // Skip rings already proven fully processed this session. If the
            // skip wraps all the way around, generation has converged — idle.
            int startRing = this.walkerRing;
            while (this.completedRings.get(this.walkerRing)) {
                this.walkerRing++;
                if (this.walkerRing > radius) this.walkerRing = 1;
                if (this.walkerRing == startRing) return;
            }
            long packed = scanRing(this.walkerAnchor, this.walkerRing, scanRemaining, submitRemaining);
            scanRemaining = (int) (packed & 0xFFFF_FFFFL);
            submitRemaining = (int) (packed >>> 32) & 0x3FFF_FFFF;
            boolean ringComplete = (packed >>> 63) != 0;
            boolean ringAllProcessed = (packed >>> 62 & 1L) != 0;
            if (ringComplete) {
                if (ringAllProcessed) {
                    this.completedRings.set(this.walkerRing);
                }
                this.walkerRing++;
            }
        }

        // If we've explored well beyond the current radius, drop the completed
        // set to bound memory. The walker will repopulate it on next pass; the
        // cost is re-processing chunks once (cheap — vanilla returns them from
        // region files immediately when they already exist on disk).
        if (this.sessionCompleted.size() > COMPLETED_SET_MAX) {
            this.sessionCompleted.clear();
        }
    }

    // Packs (ringComplete:1, ringAllProcessed:1, submitRemaining:30,
    // scanRemaining:32) so we can return all four values from one call
    // without an allocation.
    private long scanRing(ChunkPos centre, int ring, int scanRemaining, int submitRemaining) {
        if (ring == 0) {
            return pack(true, true, scanRemaining, submitRemaining);
        }
        boolean allProcessed = true;
        // Top + bottom edges (full width including corners)
        for (int dx = -ring; dx <= ring; dx++) {
            if (scanRemaining <= 0 || submitRemaining <= 0) return pack(false, false, scanRemaining, submitRemaining);
            int r = considerSubmit(centre.x + dx, centre.z - ring);
            if (r == CONSIDER_SUBMITTED) submitRemaining--;
            if (r != CONSIDER_PROCESSED) allProcessed = false;
            scanRemaining--;
            if (scanRemaining <= 0 || submitRemaining <= 0) return pack(false, false, scanRemaining, submitRemaining);
            r = considerSubmit(centre.x + dx, centre.z + ring);
            if (r == CONSIDER_SUBMITTED) submitRemaining--;
            if (r != CONSIDER_PROCESSED) allProcessed = false;
            scanRemaining--;
        }
        // Left + right edges (corners already covered)
        for (int dz = -ring + 1; dz <= ring - 1; dz++) {
            if (scanRemaining <= 0 || submitRemaining <= 0) return pack(false, false, scanRemaining, submitRemaining);
            int r = considerSubmit(centre.x - ring, centre.z + dz);
            if (r == CONSIDER_SUBMITTED) submitRemaining--;
            if (r != CONSIDER_PROCESSED) allProcessed = false;
            scanRemaining--;
            if (scanRemaining <= 0 || submitRemaining <= 0) return pack(false, false, scanRemaining, submitRemaining);
            r = considerSubmit(centre.x + ring, centre.z + dz);
            if (r == CONSIDER_SUBMITTED) submitRemaining--;
            if (r != CONSIDER_PROCESSED) allProcessed = false;
            scanRemaining--;
        }
        return pack(true, allProcessed, scanRemaining, submitRemaining);
    }

    private static long pack(boolean ringComplete, boolean allProcessed, int scan, int submit) {
        return ((ringComplete ? 1L : 0L) << 63)
                | ((allProcessed ? 1L : 0L) << 62)
                | (((long) submit & 0x3FFF_FFFFL) << 32)
                | ((long) scan & 0xFFFF_FFFFL);
    }

    // considerSubmit outcomes. PROCESSED feeds the per-ring memoisation:
    // a ring where every position is PROCESSED never needs scanning again.
    private static final int CONSIDER_PROCESSED = 0;
    private static final int CONSIDER_SUBMITTED = 1;
    private static final int CONSIDER_SKIPPED = 2;

    private int considerSubmit(int cx, int cz) {
        this.cntWalkerConsidered.incrementAndGet();
        boolean verbose = VoxyConfig.CONFIG.distantGenVerboseLogging;
        // Note: we intentionally do NOT short-circuit on level.hasChunk(cx,cz).
        // Live-ingest catches chunks Sodium adds to its render set; that's a
        // strict subset of chunks vanilla has loaded (server view distance,
        // spawn preload, /forceload, etc. can hold chunks Sodium never
        // renders). If we skipped vanilla-loaded chunks here, the ones that
        // live-ingest doesn't catch end up with no voxy data — visible as a
        // persistent blank ring around spawn after the player moves away.
        // Submitting them costs little: vanilla returns the in-memory chunk
        // immediately and insertSectionLvlIntoWorld does a value-compare so
        // overlap with live-ingest is a no-op write.
        if (isProcessed(cx, cz)) return CONSIDER_PROCESSED;
        var pos = new ChunkPos(cx, cz);
        if (this.scheduler.isQueued(pos)) return CONSIDER_SKIPPED;
        if (this.ticketManager.isPending(pos)) return CONSIDER_SKIPPED;
        boolean frontier = isCompletedTerrain(cx - 1, cz)
                || isCompletedTerrain(cx + 1, cz)
                || isCompletedTerrain(cx, cz - 1)
                || isCompletedTerrain(cx, cz + 1);
        if (!frontier) {
            return CONSIDER_SKIPPED;
        }
        this.scheduler.submit(pos);
        this.cntWalkerSubmitted.incrementAndGet();
        if (verbose) {
            me.cortex.voxy.common.Logger.info("Distant-gen walker: SUBMIT (" + cx + "," + cz + ") ring=" + this.walkerRing);
        }
        return CONSIDER_SUBMITTED;
    }

    private boolean isCompletedTerrain(int cx, int cz) {
        if (this.level.hasChunk(cx, cz)) return true;
        return isProcessed(cx, cz);
    }

    // Two-tier processed check: in-memory sessionCompleted (sub-microsecond,
    // populated as we mark chunks complete this session) backed by the
    // persistent voxy storage marker (engine.isChunkProcessed — RocksDB
    // bloom-filtered, microseconds). A positive storage hit gets cached
    // into sessionCompleted so subsequent walker passes don't repeat the
    // lookup. Means session-1-in-an-already-explored-world is cheap: prior
    // sessions' markers (and live-ingest's marker writes) populate
    // sessionCompleted on demand and we never re-process chunks voxy
    // already has.
    private boolean isProcessed(int cx, int cz) {
        long key = net.minecraft.world.level.ChunkPos.asLong(cx, cz);
        if (this.sessionCompleted.contains(key)) return true;
        if (this.engine.isChunkProcessed(cx, cz)) {
            this.sessionCompleted.add(key);
            return true;
        }
        return false;
    }

    // Called by the TicketManager when a chunk has been fully voxelised and
    // saved this session. Public so the TicketManager can wire up its
    // completion callback at construction.
    public void markCompleted(long chunkKey) {
        this.sessionCompleted.add(chunkKey);
        this.cntMarkedCompleted.incrementAndGet();
        if (VoxyConfig.CONFIG.distantGenVerboseLogging) {
            int cx = (int) chunkKey;
            int cz = (int) (chunkKey >> 32);
            me.cortex.voxy.common.Logger.info("Distant-gen markCompleted (" + cx + "," + cz + ") sessionCompleted.size=" + this.sessionCompleted.size());
        }
    }

    @Nullable
    private Player pickAnchorPlayer() {
        // First connected player in this dimension. Phase 4 expansion: per-
        // player buckets so multiplayer doesn't collapse to a single anchor.
        for (var p : this.level.players()) {
            return p;
        }
        return null;
    }

    public VoxyDistantGenScheduler scheduler() {
        return this.scheduler;
    }

    public VoxyDistantGenTicketManager ticketManager() {
        return this.ticketManager;
    }

    public WorldEngine engine() {
        return this.engine;
    }

    public ServerLevel level() {
        return this.level;
    }

    // Sweep every chunk position within `radius` chebyshev chunks of the
    // anchor player through the frontier-respecting considerSubmit check.
    // Only chunks adjacent to completed terrain are queued — same rule as
    // the auto-walker, applied in one synchronous pass. The walker
    // continues independently and will pick up newly-completed frontier
    // chunks on subsequent ticks.
    public int submitArea(int radius) {
        ChunkPos centre = anchorChunkPos();
        if (centre == null) return 0;
        int submitted = 0;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (considerSubmit(centre.x + dx, centre.z + dz) == CONSIDER_SUBMITTED) submitted++;
            }
        }
        return submitted;
    }

    @Nullable
    private ChunkPos anchorChunkPos() {
        var p = pickAnchorPlayer();
        if (p == null) return null;
        return new ChunkPos((int) p.getX() >> 4, (int) p.getZ() >> 4);
    }

    public void shutdown() {
        this.shutdown = true;
        this.driver.shutdownNow();
        this.scheduler.clear();
        this.ticketManager.shutdown();
        this.saveService.shutdown();
    }

    // Per-level registry. Phase 1 SP-only — overworld coordinator is created
    // lazily on first /voxy distantgen invocation and reused across calls.
    // Phase 3 replaces this with proper server-lifecycle hooks.
    //
    // ConcurrentHashMap (strong-keyed). Lifecycle relies on Forge events:
    // LevelEvent.Unload -> shutdownFor() and ServerStoppedEvent ->
    // shutdownAll(), both of which remove entries explicitly. A previous
    // attempt to defend against missed-event leaks by switching to
    // SynchronizedMap(WeakHashMap) was reverted: WeakHashMap silently
    // auto-removes entries when the key is reclaimed, which would drop the
    // VoxyDistantGen reference without calling its shutdown() — leaking the
    // driver thread + transitive resources. Proper weak-key defence would
    // need a Cleaner.register that calls shutdown() on reclamation; that's
    // deferred until empirically warranted.
    private static final Map<ServerLevel, VoxyDistantGen> INSTANCES = new ConcurrentHashMap<>();

    public static VoxyDistantGen getOrCreate(ServerLevel level, WorldEngine engine, ServiceManager voxyServices) {
        return INSTANCES.computeIfAbsent(level, l -> {
            var dg = new VoxyDistantGen(l, engine, voxyServices);
            dg.start();
            return dg;
        });
    }

    @Nullable
    public static VoxyDistantGen get(ServerLevel level) {
        return INSTANCES.get(level);
    }

    public static void shutdownAll() {
        var snap = new ArrayList<>(INSTANCES.values());
        INSTANCES.clear();
        for (var dg : snap) {
            try {
                dg.shutdown();
            } catch (Throwable t) {
                me.cortex.voxy.common.Logger.error("Distant-gen shutdown failed: " + t.getMessage(), t);
            }
        }
    }

    public static void shutdownFor(ServerLevel level) {
        var dg = INSTANCES.remove(level);
        if (dg != null) {
            try {
                dg.shutdown();
            } catch (Throwable t) {
                me.cortex.voxy.common.Logger.error(
                        "Distant-gen shutdown for " + level.dimension().location() + " failed: " + t.getMessage(), t);
            }
        }
    }
}
