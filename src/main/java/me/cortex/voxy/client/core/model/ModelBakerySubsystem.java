package me.cortex.voxy.client.core.model;


import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.world.other.Mapper;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;

import static org.lwjgl.opengl.GL11.glGetInteger;
import static org.lwjgl.opengl.GL30.GL_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30.GL_FRAMEBUFFER_BINDING;
import static org.lwjgl.opengl.GL30C.glBindFramebuffer;

public class ModelBakerySubsystem {
    //Redo to just make it request the block faces with the async texture download stream which
    // basicly solves all the render stutter due to the baking

    // Bake requests are tiered by the requesting section's camera distance
    // (0 = nearest, served first). The queue was previously a single FIFO,
    // which let a far section's exotic blocks bake before the models the
    // near field was waiting on — during a developed-world login flood the
    // FIFO order effectively overrode mesh priority. A request for an id
    // already queued at a worse tier re-enqueues at the better one
    // (duplicates are harmless: ModelFactory.addEntry's CAS claim makes it
    // idempotent).
    public static final int BAKE_PRIORITY_TIERS = 4;

    private final ModelStore storage = new ModelStore();
    public final ModelFactory factory;
    private final Mapper mapper;
    private final AtomicInteger blockIdCount = new AtomicInteger();
    @SuppressWarnings("unchecked")
    private final ConcurrentLinkedDeque<Integer>[] blockIdQueues = new ConcurrentLinkedDeque[BAKE_PRIORITY_TIERS];
    {
        for (int i = 0; i < BAKE_PRIORITY_TIERS; i++) {
            this.blockIdQueues[i] = new ConcurrentLinkedDeque<>();
        }
    }

    private final Thread processingThread;
    private volatile boolean isRunning = true;
    public ModelBakerySubsystem(Mapper mapper) {
        this.mapper = mapper;
        this.factory = new ModelFactory(mapper, this.storage);
        this.processingThread = new Thread(()->{//TODO replace this with something good/integrate it into the async processor so that we just have less threads overall
            while (this.isRunning) {
                this.factory.processAllThings();
                // Park until a submitter (ModelFactory.addBiome or the
                // downstream.download callback) calls our wakeup, OR until
                // the defensive 50ms timeout — whichever first. Replaces a
                // hardcoded Thread.sleep(10) polling cycle that imposed up
                // to 10ms latency on bake-result completion. parkNanos
                // permits coalesce: multiple submissions while parked wake
                // us once, multiple while running are no-op (we'll see them
                // on the next processAllThings call anyway).
                LockSupport.parkNanos(this, 50_000_000L);
            }
        }, "Model factory processor");
        // Register the wakeup BEFORE starting the thread so any early
        // submissions get notified. The factory was already constructed
        // above; setWakeup is a volatile write.
        this.factory.setWakeup(() -> LockSupport.unpark(this.processingThread));
        this.processingThread.start();
    }

    // Poll the highest-priority (nearest) non-empty tier.
    private Integer pollNextBake() {
        for (var queue : this.blockIdQueues) {
            Integer id = queue.poll();
            if (id != null) return id;
        }
        return null;
    }

    public void tick(long totalBudget) {
        long start = System.nanoTime();
        this.factory.tickAndProcessUploads();
        //Always do 1 iteration minimum
        Integer i = this.pollNextBake();
        if (i != null) {
            int j = 0;
            if (i != null) {
                int fbBinding = glGetInteger(GL_FRAMEBUFFER_BINDING);

                do {
                    this.factory.addEntry(i);
                    j++;
                    if (4<j&&(totalBudget<(System.nanoTime() - start)+50_000))//20<j||
                        break;
                    i = this.pollNextBake();
                } while (i != null);

                glBindFramebuffer(GL_FRAMEBUFFER, fbBinding);//This is done here as stops needing to set then unset the fb in the thing 1000x
            }
            this.blockIdCount.addAndGet(-j);
        }

        //TimingStatistics.modelProcess.stop();
    }

    public void shutdown() {
        this.isRunning = false;
        // Wake the worker so it sees the flag and exits, instead of waiting
        // up to 50ms for the parkNanos defensive timeout.
        LockSupport.unpark(this.processingThread);
        try {
            this.processingThread.join();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        this.factory.free();
        this.storage.free();
    }

    //This is on this side only and done like this as only worker threads call this code
    private final ReentrantLock seenIdsLock = new ReentrantLock();
    // id -> best priority tier requested so far. Replaces the old seen-set:
    // a request at a BETTER (lower) tier than previously seen re-enqueues the
    // id at that tier; requests at the same or worse tier are dropped.
    private final Int2IntOpenHashMap requestedTier = new Int2IntOpenHashMap(6000);//TODO: move to a lock free concurrent hashmap
    {
        this.requestedTier.defaultReturnValue(Integer.MAX_VALUE);
    }

    public void requestBlockBake(int blockId) {
        this.requestBlockBake(blockId, BAKE_PRIORITY_TIERS - 1);
    }

    public void requestBlockBake(int blockId, int priorityTier) {
        // Synthetic multi-cell slice ids live in a reserved high range, far above the real
        // block-state count; they are resolved to (source state + slice offset) downstream
        // in ModelFactory.addEntry, so they must skip this real-id range guard.
        if (!me.cortex.voxy.common.world.other.MultiCellSliceRegistry.isSynthetic(blockId)
                && this.mapper.getBlockStateCount() < blockId) {
            Logger.error("Error, got bakeing request for out of range state id. StateId: " + blockId + " max id: " + this.mapper.getBlockStateCount(), new Exception());
            return;
        }
        priorityTier = Math.max(0, Math.min(priorityTier, BAKE_PRIORITY_TIERS - 1));
        this.seenIdsLock.lock();
        int previousTier = this.requestedTier.get(blockId);
        if (previousTier <= priorityTier) {
            this.seenIdsLock.unlock();
            return;
        }
        this.requestedTier.put(blockId, priorityTier);
        this.seenIdsLock.unlock();
        this.blockIdQueues[priorityTier].add(blockId);
        this.blockIdCount.incrementAndGet();
    }

    public void addBiome(Mapper.BiomeEntry biomeEntry) {
        this.factory.addBiome(biomeEntry);
    }

    public void addDebugData(List<String> debug) {
        debug.add(String.format("MQ/IF/MC: %04d, %03d, %04d", this.blockIdCount.get(), this.factory.getInflightCount(),  this.factory.getBakedCount()));//Model bake queue/in flight/model baked count
    }

    public ModelStore getStore() {
        return this.storage;
    }

    public boolean areQueuesEmpty() {
        return this.blockIdCount.get()==0 && this.factory.getInflightCount() == 0;
    }

    public int getProcessingCount() {
        return this.blockIdCount.get() + this.factory.getInflightCount();
    }
}
