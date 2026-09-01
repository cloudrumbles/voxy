package me.cortex.voxy.common.thread;

import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.util.Pair;

import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

public class Service {
    private final PerThreadContextExecutor executor;
    private final ServiceManager sm;
    final long weight;
    final String name;
    final BooleanSupplier limiter;

    private final Semaphore tasks = new Semaphore(0);
    // Number of jobs that have been claimed by a worker (tryAcquire'd a tasks
    // permit) but not yet returned from executor.run(). blockTillEmpty must
    // observe this in addition to tasks.availablePermits to give callers a
    // true "all submitted work is done" signal — without it, blockTillEmpty
    // returns as soon as permits are drained, before the last in-flight job
    // actually finishes.
    private final AtomicInteger inFlightCount = new AtomicInteger();
    private volatile boolean isLive = true;
    private volatile boolean isStopping = false;

    Service(Supplier<Pair<Runnable, Runnable>> ctxSupplier, ServiceManager sm, long weight, String name, BooleanSupplier limiter) {
        this.sm = sm;
        this.weight = weight;
        this.name = name;
        this.limiter = limiter;

        this.executor = new PerThreadContextExecutor(ctxSupplier, e->sm.handleException(this, e));
    }

    public void execute() {
        if (this.isStopping) {
            Logger.error("Tried executing on a dead service");
            return;
        }
        this.tasks.release();
        this.sm.execute(this);
    }

    boolean runJob() {
        if (this.isStopping||!this.isLive) {
            return false;
        }
        if (!this.tasks.tryAcquire()) {
            //Failed to get the job, probably due to a race condition
            return false;
        }
        this.inFlightCount.incrementAndGet();
        try {
            if (!this.executor.run()) {//Run the job
                throw new IllegalStateException("Executor failed to run");
            }
        } finally {
            // try/finally: a throwing job still decrements, otherwise
            // blockTillEmpty would wait forever for the leaked count.
            this.inFlightCount.decrementAndGet();
        }
        return true;
    }

    public boolean isLive() {
        return this.isLive&&!this.isStopping;
    }

    public int numJobs() {
        return this.tasks.availablePermits();
    }

    public void blockTillEmpty() {
        // Wait for BOTH no pending permits AND no in-flight jobs. Without
        // observing inFlightCount, a caller (e.g. WorldImporter at its
        // completion checkpoint) would return as soon as all jobs were
        // picked up by workers — before the last one actually completes.
        // 10ms poll is acceptable: this is a rare completion-wait, not a
        // hot path.
        while (this.isLive() && (this.numJobs() != 0 || this.inFlightCount.get() != 0)) {
            Thread.yield();
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }

    // Shutdown sequence — the step ORDERING is load-bearing for correctness.
    // Reordering will break the totalJobs/permits accounting in ServiceManager.
    //
    //   1. isStopping=true:    volatile write. Service.isLive() flips to false
    //                          immediately; runAJob0 skips us, runJob refuses
    //                          to acquire. No new execute() calls succeed.
    //   2. removeService:      synchronized in ServiceManager; removes us from
    //                          the rotation. Any worker that already had a
    //                          stale reference still hits step 1's check.
    //   3. executor.shutdown:  waits for any in-flight executor.run() that
    //                          started before step 1's check was visible.
    //                          After this returns, no executor.run is active.
    //   4. drainPermits:       captures the remaining unconsumed permits. By
    //                          step 3 nobody is consuming any more; this is
    //                          a stable count.
    //   5. isLive=false:       final state marker (informational; isLive()
    //                          already returned false via step 1).
    //   6. remJobs(remaining): decrements ServiceManager.totalJobs by the
    //                          drained count, matching the totalJobs+1 each
    //                          execute() did. Must happen AFTER drainPermits
    //                          for the count to be accurate.
    //
    // Future refactors that shuffle these steps will leak counts or fire the
    // throw at ServiceManager:141 (service still .isLive() during manager
    // shutdown). Don't reorder without re-verifying the chain.
    public int shutdown() {
        if (this.isStopping) {
            throw new IllegalStateException("Service not live");
        }
        this.isStopping = true;//First mark the service as stopping
        this.sm.removeService(this);//Remove the service this is so that new jobs are never executed
        this.executor.shutdown();//Await shutdown of all running jobs
        int remaining = this.tasks.drainPermits();//Drain the remaining tasks to 0
        this.isLive = false;//Mark the service as dead
        this.sm.remJobs(remaining);
        return remaining;
    }

    public boolean steal() {
        if (!this.tasks.tryAcquire()) {
            return false;
        }
        this.sm.remJobs(1);
        return true;
    }

    public int drain() {
        int tasks = this.tasks.drainPermits();
        if (tasks != 0) {
            this.sm.remJobs(tasks);
        }
        return tasks;
    }
}
