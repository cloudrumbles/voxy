package me.cortex.voxy.common.thread;

import me.cortex.voxy.common.util.TrackedObject;

import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.IntSupplier;

// Priority-based multi-semaphore: pools multiple thread pools together while
// prioritising the work the owner block was meant for.
//
// CURRENT STATUS: load-bearing for voxy's UnifiedServiceThreadPool — worker
// threads block on selfBlock.acquire() to wait for jobs. Despite the author's
// disclaimer on line ~49, it does in practice work for voxy's internal usage
// (one block owner = the voxy worker pool, no external sodium integration on
// Forge 1.20.1 — that integration is disabled via MixinChunkJobQueue's no-op
// redirect).
//
// KNOWN CONCERNS (audit findings, currently latent — not causing observable
// issues on this workload):
//
// 1. Cascading pooledRelease floods (line ~125). pooledRelease(permits)
//    iterates every Block and releases `permits` to each. With N blocks and M
//    submissions, queue inflates by N*M; workers wake spuriously, do
//    tryAcquire, fail, and spin. O(N) per submission. Not currently observable
//    because voxy has exactly one block (selfBlock from UnifiedServiceThreadPool).
//    Would matter only if multiple block-owning pools were ever integrated.
//
// 2. runJob=false branch (~line 60-65). Acquires the local permit but then
//    only tries to grab a block permit, accepting failure. The author's own
//    comment marks this as "technicanlly/actually a failure state." Drift
//    risk if the failure path is taken under contention. Today no caller uses
//    acquire(false) — the workerThread always calls bare acquire() (=
//    acquire(true)), so this branch is unreached.
//
// 3. The acquire(true) hot path uses the "no idea if this works" approach
//    (line ~49 author note). Empirically it does for voxy's single-block
//    usage; the failure modes would surface as worker starvation or spurious
//    wake-ups under multi-block contention, neither of which we currently
//    have configurations to hit.
//
// PROPER REWRITE (deferred to a dedicated session): the natural replacement
// is a LinkedBlockingQueue<Runnable> for the pooled work plus per-block
// Semaphores for local-priority work, OR a single Phaser with priority
// awareness. Either design needs a careful state-machine spec covering
// shutdown, dynamic thread-count changes (UnifiedServiceThreadPool's
// setNumThreads spawns/retires threads at runtime via selfBlock.release of
// negative permits — a quirky idiom this rewrite would need to preserve or
// replace), and the IntSupplier-returning-status-code contract used by the
// ServiceManager dispatcher.
public class MultiThreadPrioritySemaphore {
    public static final class Block extends TrackedObject {
        private final Semaphore blockSemaphore = new Semaphore(0);//The work pool semaphore
        private final Semaphore localSemaphore = new Semaphore(0);//The local semaphore
        //private final AtomicInteger debt = new AtomicInteger();//the debt of the work pool semphore with respect to the usage
        private final MultiThreadPrioritySemaphore man;

        Block(MultiThreadPrioritySemaphore man) {
            this.man = man;
        }

        public void release(int permits) {
            //release local then block to prevent race conditions
            this.localSemaphore.release(permits);
            this.blockSemaphore.release(permits);
        }

        public void acquire() {
            this.acquire(true);
        }
        public void acquire(boolean runJob) {//Block until a permit for this block is availbe, other jobs maybe executed while we wait
            /*
            while (true) {
                this.blockSemaphore.acquireUninterruptibly();//Block on all
                if (this.localSemaphore.tryAcquire()) {//We prioritize locals first
                    return;
                }
                if (runJob) {
                    //It wasnt a local job so run
                    this.man.tryRun(this);
                } else {
                    this.blockSemaphore.release(1);
                    Thread.onSpinWait();
                    Thread.yield();
                }
            }*/

            //Absolutly no idea if this shitty thing functions correctly... at all, it very much probably doesnt
            while (true) {
                if (runJob) {
                    this.blockSemaphore.acquireUninterruptibly();//Block on all
                    if (this.localSemaphore.tryAcquire()) {//We prioritize locals first
                        return;
                    }
                    if (this.man.tryRun(this)) {//Returns true if it captured a local job
                        break;
                    }
                } else {
                    this.localSemaphore.acquireUninterruptibly();
                    if (!this.blockSemaphore.tryAcquire()) {
                        //This is technicanlly/actually a failure state cause blockSemaphore could have more
                    }
                    break;
                }
            }
        }


        public void free() {
            this.man.freeBlock(this);
            this.free0();
        }

        public int availablePermits() {
            return this.localSemaphore.availablePermits();
        }

        public boolean tryAcquire() {
            if (this.localSemaphore.availablePermits()==0) return false;//Quick exit
            if (!this.blockSemaphore.tryAcquire()) return false;//There is definatly none
            if (this.localSemaphore.tryAcquire()) {
                //we acquired a proper permit
                return true;
            } else {
                //We must release the other permit as we dont do processing here
                this.blockSemaphore.release(1);
                return false;
            }
        }
    }

    private final Semaphore pooledSemaphore = new Semaphore(0);
    private final IntSupplier executor;

    private volatile Block[] blocks = new Block[0];

    public MultiThreadPrioritySemaphore(IntSupplier executor) {
        this.executor = executor;
    }

    public synchronized Block createBlock() {
        var block = new Block(this);
        var blocks = Arrays.copyOf(this.blocks, this.blocks.length+1);
        blocks[blocks.length-1] = block;
        this.blocks = blocks;
        return block;
    }

    private synchronized void freeBlock(Block block) {
        var ob = this.blocks;
        var blocks = new Block[ob.length-1];
        int j = 0;
        for (int i = 0; i <= blocks.length; i++) {
            if (ob[i] != block) {
                blocks[j++] = ob[i];
            }
        }
        if (j != blocks.length) {
            throw new IllegalStateException("Could not find the service in the services array");
        }
        this.blocks = blocks;
    }

    public void pooledRelease(int permits) {
        this.pooledSemaphore.release(permits);
        for (var block : this.blocks) {
            block.blockSemaphore.release(permits);
        }
    }

    private boolean tryRun(Block block) {
        if (!this.pooledSemaphore.tryAcquire()) {//No jobs for the unified pool
            return false;
        }
        /*
        for (var otherBlock : this.blocks) {
            if (otherBlock != block) {
                block.debt.incrementAndGet();
            }
        }*/
        //Run the pooled job
        while (true) {
            int status = this.executor.getAsInt();
            if (status == 0) return false;//We finished pure and true
            if (status == 1) return false;// we didnt run a job because there either wasnt any or no services exist
            if (2 <= status) {//2 and 3 mean failed to find a service that can currently run, but should try again after a delay
                try {
                    if (block.localSemaphore.tryAcquire(10, TimeUnit.MILLISECONDS)) {//Await 10 millis for a local job to come in
                        //We do this confusing thing
                        block.blockSemaphore.tryAcquire();//Try acquire the block that we just got
                        this.pooledRelease(1);//We need to release back into the pool
                        return true;
                    }
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
        }
    }
}
