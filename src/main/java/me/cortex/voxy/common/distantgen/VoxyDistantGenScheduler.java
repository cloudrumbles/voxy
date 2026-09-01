package me.cortex.voxy.common.distantgen;

import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

// Distance-bucketed, weighted-round-robin scheduler with look-direction
// weighting. Port of DH-au-naturel's WorldGenerationQueue
// (coreSubProjects/core/.../core/generation/WorldGenerationQueue.java), with
// LOD-tree splitting removed because voxy is chunk-granular.
//
// Algorithm:
//   - Submitted tasks land in one of 6 distance buckets by Chebyshev chunk-
//     distance from the configured target position.
//   - Each scheduler tick picks one bucket using a 17-slot weight table
//     ({0,0,0,0,0,0,0,0,1,1,1,1,2,2,3,4,5}) — bucket 0 gets ~47% of picks,
//     distant buckets each get at least one slot per cycle so they don't
//     starve while the player is moving and refreshing bucket 0.
//   - Within the chosen bucket, the task whose weighted distance is smallest
//     wins, where weight = base * (1.0 + 0.25*(1.0 - dot(taskDir, lookDir)))
//     — so tasks in front of the camera have multiplier 1.0, behind have 1.5.
//   - When the player moves more than REBUCKET_MOVEMENT_THRESHOLD chunks,
//     all tasks are re-bucketed.
//   - At MAX_WAITING capacity, new tasks closer than the furthest bucket
//     evict from a further bucket so close-priority is preserved.
//
// Threading:
//   - submit() / setTargetPos() / setLookDirection() callable from any
//     thread.
//   - tick() must be called from a single thread (typically the server tick
//     dispatcher); it dispatches concurrent ticket-manager requests up to
//     maxInFlight.
public class VoxyDistantGenScheduler {
    private static final int[] BUCKET_BOUNDARIES = {8, 16, 32, 64, 128};
    private static final int BUCKET_COUNT = BUCKET_BOUNDARIES.length + 1;
    private static final int[] BUCKET_WEIGHT_TABLE = {0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 3, 4, 5};
    private static final int REBUCKET_MOVEMENT_THRESHOLD = 16;
    private static final int MAX_WAITING = 500;

    private final VoxyDistantGenTicketManager ticketManager;

    // Source of truth for "task is queued"; iterated by buckets but
    // membership is decided here so eviction stays consistent.
    private final ConcurrentHashMap<Long, Task> waiting = new ConcurrentHashMap<>();

    @SuppressWarnings("unchecked")
    private final ConcurrentHashMap<Long, Task>[] buckets = new ConcurrentHashMap[BUCKET_COUNT];

    private volatile ChunkPos targetPos = new ChunkPos(0, 0);
    private volatile ChunkPos lastBucketTarget = null;
    @Nullable private volatile Vec3 lookDir = null;

    private final AtomicLong bucketSelectionCounter = new AtomicLong(0);

    private volatile int maxInFlight = 1;

    private record Task(long key, ChunkPos pos) {}

    // Diagnostic counter — incremented every time tick() dispatches a task
    // to the ticket manager. Read by VoxyDistantGen's heartbeat.
    public final java.util.concurrent.atomic.AtomicLong cntDispatched = new java.util.concurrent.atomic.AtomicLong();

    public VoxyDistantGenScheduler(VoxyDistantGenTicketManager ticketManager) {
        this.ticketManager = ticketManager;
        for (int i = 0; i < BUCKET_COUNT; i++) {
            this.buckets[i] = new ConcurrentHashMap<>();
        }
    }

    public void setMaxInFlight(int max) {
        this.maxInFlight = Math.max(1, max);
    }

    public void setTargetPos(ChunkPos target) {
        this.targetPos = target;
    }

    public void setLookDirection(@Nullable Vec3 lookDir) {
        // Stored as a unit vector in xz. Normalisation deferred until use to
        // avoid recomputing on every camera-yaw update.
        this.lookDir = lookDir;
    }

    public int waitingCount() {
        return this.waiting.size();
    }

    public boolean isQueued(ChunkPos pos) {
        return this.waiting.containsKey(pos.toLong());
    }

    // Submit a chunk position for distant generation. Returns true if the task
    // was added (or already pending), false if rejected (e.g. duplicate of an
    // in-flight request). Same-position submissions are deduplicated.
    public boolean submit(ChunkPos pos) {
        long key = pos.toLong();
        if (this.ticketManager.isPending(pos)) {
            return true;
        }
        if (this.waiting.containsKey(key)) {
            return true;
        }

        var target = this.targetPos;
        int newBucket = calculateBucketIndex(pos, target);

        if (this.waiting.size() >= MAX_WAITING) {
            // Try to evict from a further bucket. If our bucket is already the
            // furthest, the task is still added (soft cap).
            if (newBucket < BUCKET_COUNT - 1) {
                tryEvictFromFurtherBucket(newBucket);
            }
        }

        var task = new Task(key, pos);
        if (this.waiting.putIfAbsent(key, task) != null) {
            return true;
        }
        this.buckets[newBucket].put(key, task);
        return true;
    }

    // Should be called periodically (e.g. once per server tick). Dispatches
    // up to (maxInFlight - currentInFlight) tickets to the ticket manager.
    public void tick() {
        if (this.waiting.isEmpty()) return;

        var target = this.targetPos;
        if (needsRebucketing(target)) {
            rebucketAll(target);
        }

        int budget = this.maxInFlight - this.ticketManager.inFlightCount();
        if (budget <= 0) return;

        for (int i = 0; i < budget; i++) {
            var task = pickNextTask(target);
            if (task == null) return;
            this.cntDispatched.incrementAndGet();
            if (me.cortex.voxy.client.config.VoxyConfig.CONFIG.distantGenVerboseLogging) {
                me.cortex.voxy.common.Logger.info("Distant-gen scheduler: dispatch (" + task.pos.x + "," + task.pos.z + ") inFlight=" + this.ticketManager.inFlightCount() + " max=" + this.maxInFlight);
            }
            this.ticketManager.request(task.pos);
        }
    }

    @Nullable
    private Task pickNextTask(ChunkPos target) {
        // Weighted round-robin pick of bucket.
        int slot = (int) Math.floorMod(this.bucketSelectionCounter.getAndIncrement(), BUCKET_WEIGHT_TABLE.length);
        int preferred = BUCKET_WEIGHT_TABLE[slot];

        ConcurrentHashMap<Long, Task> bucket = !this.buckets[preferred].isEmpty()
                ? this.buckets[preferred]
                : null;
        if (bucket == null) {
            // Fall through nearest-first so close-priority remains a fallback.
            for (int i = 0; i < BUCKET_COUNT; i++) {
                if (!this.buckets[i].isEmpty()) {
                    bucket = this.buckets[i];
                    break;
                }
            }
        }
        if (bucket == null) return null;

        Task best = selectBestInBucket(bucket, target, this.lookDir);
        if (best == null) return null;

        // Remove from both maps so we don't dispatch twice.
        this.waiting.remove(best.key, best);
        for (int i = 0; i < BUCKET_COUNT; i++) {
            this.buckets[i].remove(best.key, best);
        }
        return best;
    }

    @Nullable
    private static Task selectBestInBucket(ConcurrentHashMap<Long, Task> bucket, ChunkPos target, @Nullable Vec3 lookDir) {
        Task best = null;
        double bestWeight = Double.POSITIVE_INFINITY;
        double lx = 0, lz = 0, llen = 0;
        if (lookDir != null) {
            lx = lookDir.x;
            lz = lookDir.z;
            llen = Math.sqrt(lx * lx + lz * lz);
        }
        boolean useLook = llen > 1e-6;
        if (useLook) {
            lx /= llen;
            lz /= llen;
        }

        for (Task task : bucket.values()) {
            int dx = task.pos.x - target.x;
            int dz = task.pos.z - target.z;
            double base = Math.max(Math.abs(dx), Math.abs(dz));
            double weight = base;
            if (useLook && base > 0) {
                double len = Math.sqrt(dx * dx + dz * dz);
                if (len > 0) {
                    double dot = (dx / len) * lx + (dz / len) * lz;
                    weight = base * (1.0 + 0.25 * (1.0 - dot));
                }
            }
            if (weight < bestWeight) {
                bestWeight = weight;
                best = task;
            }
        }
        return best;
    }

    private static int calculateBucketIndex(ChunkPos taskPos, ChunkPos targetPos) {
        int dx = Math.abs(taskPos.x - targetPos.x);
        int dz = Math.abs(taskPos.z - targetPos.z);
        int dist = Math.max(dx, dz);
        for (int i = 0; i < BUCKET_BOUNDARIES.length; i++) {
            if (dist < BUCKET_BOUNDARIES[i]) return i;
        }
        return BUCKET_COUNT - 1;
    }

    private boolean needsRebucketing(ChunkPos current) {
        var last = this.lastBucketTarget;
        if (last == null) return true;
        int dx = Math.abs(last.x - current.x);
        int dz = Math.abs(last.z - current.z);
        return Math.max(dx, dz) >= REBUCKET_MOVEMENT_THRESHOLD;
    }

    private void rebucketAll(ChunkPos newTarget) {
        for (int i = 0; i < BUCKET_COUNT; i++) {
            this.buckets[i].clear();
        }
        for (Task task : this.waiting.values()) {
            int idx = calculateBucketIndex(task.pos, newTarget);
            this.buckets[idx].put(task.key, task);
        }
        this.lastBucketTarget = newTarget;
    }

    private void tryEvictFromFurtherBucket(int closerThanBucket) {
        for (int i = BUCKET_COUNT - 1; i > closerThanBucket; i--) {
            var bucket = this.buckets[i];
            if (bucket.isEmpty()) continue;
            Iterator<Map.Entry<Long, Task>> it = bucket.entrySet().iterator();
            if (!it.hasNext()) continue;
            var entry = it.next();
            long key = entry.getKey();
            var task = entry.getValue();
            if (bucket.remove(key, task)) {
                this.waiting.remove(key, task);
                return;
            }
        }
    }

    // Clear all queued submissions. Tickets already in flight are not affected
    // — those finish (or are released) via the ticket manager's own lifecycle.
    public void clear() {
        this.waiting.clear();
        for (int i = 0; i < BUCKET_COUNT; i++) {
            this.buckets[i].clear();
        }
    }
}
