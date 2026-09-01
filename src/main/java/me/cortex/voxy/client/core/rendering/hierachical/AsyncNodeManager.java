package me.cortex.voxy.client.core.rendering.hierachical;

import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntConsumer;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import me.cortex.voxy.client.TimingStatistics;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.gl.shader.Shader;
import me.cortex.voxy.client.core.gl.shader.ShaderType;
import me.cortex.voxy.client.core.rendering.GeometryCache;
import me.cortex.voxy.client.core.rendering.SectionUpdateRouter;
import me.cortex.voxy.client.core.rendering.building.BuiltSection;
import me.cortex.voxy.client.core.rendering.building.RenderGenerationService;
import me.cortex.voxy.client.core.rendering.section.geometry.BasicAsyncGeometryManager;
import me.cortex.voxy.client.core.rendering.section.geometry.BasicSectionGeometryData;
import me.cortex.voxy.client.core.rendering.section.geometry.IGeometryData;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.util.AllocationArena;
import me.cortex.voxy.common.util.MemoryBuffer;
import me.cortex.voxy.common.util.UnsafeUtil;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import org.lwjgl.system.MemoryUtil;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.StampedLock;

import static org.lwjgl.opengl.ARBUniformBufferObject.glBindBufferBase;
import static org.lwjgl.opengl.GL30C.glUniform1ui;
import static org.lwjgl.opengl.GL42C.GL_UNIFORM_BARRIER_BIT;
import static org.lwjgl.opengl.GL42C.glMemoryBarrier;
import static org.lwjgl.opengl.GL43C.*;

//TODO: create an "async upload stream", that is, the upload stream is a raw mapped buffer pointer that can be written to
// which is then synced to the gpu on "render thread sync",


//An "async host" for a NodeManager, has specific synchonius entry and exit points
// this is done off thread to reduce the amount of work done on the render thread, improving frame stability and reducing runtime overhead
public class AsyncNodeManager {
    private static final VarHandle RESULT_HANDLE;
    private static final VarHandle RESULT_CACHE_1_HANDLE;
    private static final VarHandle RESULT_CACHE_2_HANDLE;
    static {
        try {
            RESULT_HANDLE = MethodHandles.lookup().findVarHandle(AsyncNodeManager.class, "results", SyncResults.class);
            RESULT_CACHE_1_HANDLE = MethodHandles.lookup().findVarHandle(AsyncNodeManager.class, "resultCache1", SyncResults.class);
            RESULT_CACHE_2_HANDLE = MethodHandles.lookup().findVarHandle(AsyncNodeManager.class, "resultCache2", SyncResults.class);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    private final Thread thread;
    public final int maxNodeCount;
    private final long geometryCapacity;
    private volatile boolean running = true;

    // Once-per-session guard. Pre-fix, the underflow surfaced via a benign
    // submit/drain race: queue.add(item) then wc.getAndIncrement() are not
    // atomic, so the worker could poll and decrement before the producer
    // landed its increment. Counter accounting is now structural — see run()
    // — so this should never fire. If it does, there is a real new bug.
    private final AtomicBoolean negativeCounterWarned = new AtomicBoolean(false);

    private final NodeManager manager;
    private final BasicAsyncGeometryManager geometryManager;
    private final IGeometryData geometryData;
    private final SectionUpdateRouter router;

    // 1 GiB cap on the CPU-side native-memory cache of evicted BuiltSections.
    // The cache only evicts on put-overflow (LRU-first), no time-based reaping;
    // a session-long fly-through can pin the full cap indefinitely until the
    // render system tears down. 1 GiB still covers thousands of typical LOD
    // sections (re-display benefit preserved) while leaving RSS headroom for
    // storage backends, mesher scratch, upload/download streams, mapper.
    private final GeometryCache geometryCache = new GeometryCache(1L<<30);

    private final AtomicInteger workCounter = new AtomicInteger();

    @SuppressWarnings("FieldMayBeFinal")
    private volatile SyncResults results = null, resultCache1 = new SyncResults(), resultCache2 = new SyncResults();


    //locals for during iteration
    private final IntOpenHashSet tlnIdChange = new IntOpenHashSet();//"Encoded" add/remove id, first bit indicates if its add or remove, 1 is add
    //Top bit indicates clear or reset
    private final IntOpenHashSet cleanerIdResetClear = new IntOpenHashSet();//Tells the cleaner if it needs to clear the id to 0, or reset the id to the current frame

    private boolean needsWaitForSync = false;

    public AsyncNodeManager(int maxNodeCount, IGeometryData geometryData, RenderGenerationService renderService) {
        //Note the current implmentation of ISectionWatcher is threadsafe
        //Note: geometry data is the data store/source, not the management, it is just a raw store of data
        // it MUST ONLY be accessed on the render thread
        // AsyncNodeManager will use an AsyncGeometryManager as the manager for the data store, and sync the results on the render thread
        this.geometryData = geometryData;
        this.geometryCapacity = ((BasicSectionGeometryData)geometryData).getGeometryCapacityBytes();

        this.maxNodeCount = maxNodeCount;

        this.thread = new Thread(()->{
            try {
                while (this.running) {
                    this.run();
                }
            } catch (Exception e) {
                Logger.error("Critical error occurred in async processor, things will be broken", e);
            }
        });
        this.thread.setName("Async Node Manager");

        this.geometryManager = new BasicAsyncGeometryManager(((BasicSectionGeometryData)geometryData).getMaxSectionCount(), this.geometryCapacity);

        this.router = new SectionUpdateRouter();
        this.router.setCallbacks(pos->{//On initial render gen, try get from geometry cache
            var cachedGeometry = this.geometryCache.remove(pos);
            if (cachedGeometry != null) {//Use the cached geometry
                this.submitGeometryResult(cachedGeometry);
            } else {//Else we need to request it
                renderService.enqueueTask(pos);
            }
        }, renderService::enqueueTask, this::submitChildChange);
        renderService.setResultConsumer(this::submitGeometryResult);

        this.manager = new NodeManager(maxNodeCount, this.geometryManager, this.router);

        //Dont do the move... is just to much effort
        this.manager.setClear(new NodeManager.ICleaner() {
            @Override
            public void alloc(int id) {
                AsyncNodeManager.this.cleanerIdResetClear.remove(id);//Remove clear
                AsyncNodeManager.this.cleanerIdResetClear.add(id|(1<<31));//Add reset
            }

            @Override
            public void move(int from, int to) {
                //noop (sorry :( will cause some perf loss/incorrect cleaning )
            }

            @Override
            public void free(int id) {
                AsyncNodeManager.this.cleanerIdResetClear.remove(id|(1<<31));//Remove reset
                AsyncNodeManager.this.cleanerIdResetClear.add(id);//Add clear
            }
        });
        this.manager.setTLNCallbacks(id->{
            if (!this.tlnIdChange.remove(id)) {
                if (!this.tlnIdChange.add(id|(1<<31))) {
                    throw new IllegalStateException();
                }
            }
        }, id -> {
            if (!this.tlnIdChange.remove(id|(1<<31))) {
                if (!this.tlnIdChange.add(id)) {
                    throw new IllegalStateException();
                }
            }
        });
    }

    private SyncResults getMakeResultObject() {
        SyncResults resultSet = (SyncResults)RESULT_CACHE_1_HANDLE.getAndSet(this, null);
        if (resultSet == null) {//Not in the first object
            resultSet = (SyncResults)RESULT_CACHE_2_HANDLE.getAndSet(this, null);
        }
        if (resultSet == null) {
            throw new IllegalStateException("There should always be an object in the result set cache pair");
        }
        //Reset everything to default
        resultSet.reset();
        return resultSet;
    }

    private final Shader scatterWrite = Shader.make()
            .define("INPUT_BUFFER_BINDING", 0)
            .define("OUTPUT_BUFFER1_BINDING", 1)
            .define("OUTPUT_BUFFER2_BINDING", 2)
            .add(ShaderType.COMPUTE, "voxy:util/scatter.comp")
            .compile();

    private final Shader multiMemcpy = Shader.make()
            .define("INPUT_HEADER_BUFFER_BINDING", 0)
            .define("INPUT_DATA_BUFFER_BINDING", 1)
            .define("OUTPUT_BUFFER_BINDING", 2)
            .add(ShaderType.COMPUTE, "voxy:util/memcpy.comp")
            .compile();

    private void run() {
        this.cycleCount.incrementAndGet();
        if (this.workCounter.get() == 0) {
            //TODO: here, instead of parking, we can do more work on other sub-tasks such as filtering the mesh build queue
            LockSupport.park();
            if (!this.running) {
                return;
            }
            // Previous code Thread.sleep(10)'d here "for better batching" —
            // a hardcoded 10ms latency floor on every wake. The poll loops
            // below drain everything currently in the queues; items arriving
            // during processing get picked up in the next outer-loop iteration.
            // Removing the artificial delay; instrumentation (cyclesPerSec /
            // workPerCycle in addDebug) lets us verify batching wasn't load-
            // bearing in practice — if work-per-cycle stays large under load,
            // the natural batching is fine; if it drops to 1 with throughput
            // drop, we'd need a smarter accumulator (Condition-based wait
            // for "at least N items or T elapsed"), not a blind sleep.
        }

        if (!this.running) {
            return;
        }

        // Atomically claim all currently-pending work. After this point new
        // submits can still increment workCounter; those will be claimed on
        // the next cycle. We never decrement workCounter ourselves — its only
        // transitions are producer-increments and worker-claims-to-zero, so
        // it can never go negative. This replaces the prior
        // wc.addAndGet(-workDone) scheme that was vulnerable to the submit
        // race (queue.add and wc.getAndIncrement are not atomic — worker
        // could poll an item before the producer landed its increment, then
        // decrement by more than had been counted).
        //
        // Cost: occasional "false-positive" cycle where the claim returns N
        // but the matching items were already drained in the previous cycle
        // (producer's wc++ landed after the queue was drained). The next
        // cycle drains zero, returns, and parks. Bounded: at most one wasted
        // cycle per false-positive. Each wasted cycle is ~4 poll()s on empty
        // queues + 1 atomic — tens of nanoseconds.
        int claimed = this.workCounter.getAndSet(0);
        if (claimed == 0) {
            return;
        }

        int workDone = 0;

        {
            LongOpenHashSet add = null;
            LongOpenHashSet rem = null;
            long stamp = this.tlnLock.writeLock();

            if (!this.tlnAdd.isEmpty()) {
                add = new LongOpenHashSet(this.tlnAdd);
                this.tlnAdd.clear();
            }
            if (!this.tlnRem.isEmpty()) {
                rem = new LongOpenHashSet(this.tlnRem);
                this.tlnRem.clear();
            }

            this.tlnLock.unlockWrite(stamp);
            int work = 0;
            if (rem != null) {
                var iter = rem.longIterator();
                while (iter.hasNext()) {
                    this.manager.removeTopLevelNode(iter.nextLong());
                    work++;
                }
            }

            if (add != null) {
                var iter = add.longIterator();
                while (iter.hasNext()) {
                    this.manager.insertTopLevelNode(iter.nextLong());
                    work++;
                }
            }

            workDone += work;
        }

        do {
            var job = this.childUpdateQueue.poll();
            if (job == null)
                break;
            this.childUpdateQueueSize.decrementAndGet();
            workDone++;
            this.manager.processChildChange(job.key, job.getNonEmptyChildren());
            job.release();
        } while (true);


        //Limit uploading as well as by geometry capacity being available
        // must have 50 mb of free geometry space to upload
        for (int limit = 0; limit < 300 && ((this.geometryCapacity-this.geometryManager.getGeometryUsedBytes())>50_000_000L); limit++) {
            var job = this.geometryUpdateQueue.poll();
            if (job == null)
                break;
            this.geometryUpdateQueueSize.decrementAndGet();
            workDone++;
            this.manager.processGeometryResult(job);
        }

        while (true) {//Process all request batches
            var job = this.requestBatchQueue.poll();
            if (job == null)
                break;
            this.requestBatchQueueSize.decrementAndGet();
            workDone++;
            long ptr = job.address;
            int count = MemoryUtil.memGetInt(ptr);
            ptr += 8;//Its 8 to keep alignment
            if (job.size < count * 8L + 8) {
                throw new IllegalStateException();
            }
            for (int i = 0; i < count; i++) {
                long pos = ((long) MemoryUtil.memGetInt(ptr)) << 32; ptr += 4;
                pos |= Integer.toUnsignedLong(MemoryUtil.memGetInt(ptr)); ptr += 4;
                this.manager.processRequest(pos);
            }
            job.free();
        }


        do {
            var job = this.removeBatchQueue.poll();
            if (job == null)
                break;
            this.removeBatchQueueSize.decrementAndGet();
            workDone++;
            long ptr = job.address;
            int zeroCount = 0;
            for (int i = 0; i < NodeCleaner.OUTPUT_COUNT; i++) {
                long pos = ((long) MemoryUtil.memGetInt(ptr)) << 32; ptr += 4;
                pos |= Integer.toUnsignedLong(MemoryUtil.memGetInt(ptr)); ptr += 4;

                if (pos == -1) {
                    //TODO: investigate how or what this happens
                    continue;
                }

                if (pos == 0 && zeroCount++>0) {
                    Logger.error("Remove node pos is 0 " + zeroCount + " times, this is really bad, please report" );
                    continue;
                }

                this.manager.removeNodeGeometry(pos);
            }
            job.free();
        } while (true);

        // Defensive: workCounter is structurally non-negative under the
        // getAndSet(0)-claim scheme above. If we ever observe a negative
        // value here, there's a new bug worth investigating. The clamp +
        // once-per-session log is retained as a tripwire.
        if (this.workCounter.get() < 0) {
            this.workCounter.updateAndGet(v -> Math.max(0, v));
            if (this.negativeCounterWarned.compareAndSet(false, true)) {
                Logger.error("AsyncNodeManager workCounter went negative under getAndSet(0) accounting — this indicates a new race; please investigate. Counter clamped to 0. Once per session.");
            }
        }

        this.totalWorkProcessed.addAndGet(workDone);
        this.maybeLogHeartbeat();

        if (workDone == 0) {//Nothing happened, which is odd, but just return
            //Should probably log that nothing happened, at least once
            return;
        }
        //=====================
        //process output events and atomically sync to results

        //Events into manager
        //manager.insertTopLevelNode();
        //manager.removeTopLevelNode();

        //manager.removeNodeGeometry();

        //manager.processRequest();
        //manager.processChildChange();
        //manager.processGeometryResult();


        //Outputs from manager
        //manager.setClear();
        //manager.setTLNCallbacks();

        //manager.writeChanges()


        //Run in a loop, process all the input events, collect the output events merge with previous and publish
        // note: inner event processing is a loop, is.. should be synced to attomic/volatile variable that is being watched
        // when frametime comes around, want to exit out as quick as possible, or make the event publishing
        // "effectivly immediately", that is, atomicly swap out the render side event updates

        //like
        // var current = <new events>
        // var old = getAndSet(this.events, null);
        // if (old != null) {current = merge(old, current);}
        // getAndSet(this.events, current);
        // if (old == null) {cleanAllEventsUpToThisPoint();}//(i.e. clear any buffers or maps containing data revolving around uncommited render thread data events)

        // this creates a lock free event update loop, allowing the render thread to never stall on waiting

        //TODO: NOTE: THIS MUST BE A SINGLE OBJECT THAT IS EXCHANGED
        // for it to be effectivly synchonized all outgoing events/effects _MUST_ happen at the same time
        // for this to be lock free an entire object containing ALL the events that must be synced must be exchanged


        //TODO: also note! this can be done for the processing of rendered out block models!!
        // (it might be able to also be put in this thread, maybe? but is proabably worth putting in own thread for latency reasons)
        if (this.needsWaitForSync) {
            // Wait for the render thread (tick() below) to consume the previous
            // results. Previous implementation Thread.sleep(10)'d in a tight
            // loop, costing up-to-10ms per wait. Switching to parkNanos with
            // unpark from the consumer matches the rest of this file's
            // park/unpark idiom and drops worst-case wait to ~immediately
            // after tick() finishes its getAndSet. The 50ms defensive timeout
            // keeps us moving if a notify is missed (worker re-checks the
            // RESULT_HANDLE after every wake).
            while (RESULT_HANDLE.get(this) != null && this.running) {
                LockSupport.parkNanos(this, 50_000_000L);
            }
        }


        var prev = (SyncResults) RESULT_HANDLE.getAndSet(this, null);
        SyncResults results = null;
        if (prev == null) {
            this.needsWaitForSync = false;
            results = this.getMakeResultObject();
            //Clear old data (if it exists), create a new result set
            results.tlnDelta.addAll(this.tlnIdChange);
            this.tlnIdChange.clear();

            if (!this.geometryManager.getUploads().isEmpty()){//Put in new data into sync set
                var iter = this.geometryManager.getUploads().int2ObjectEntrySet().fastIterator();
                while (iter.hasNext()) {
                    var val = iter.next();
                    results.geometryUpload.upload(val.getIntKey(), val.getValue());
                    val.getValue().free();
                }
                this.geometryManager.getUploads().clear();
            }

            this.geometryManager.getHeapRemovals().clear();//We dont do removals on new data (as there is "none")
            results.cleanerOperations.addAll(this.cleanerIdResetClear); this.cleanerIdResetClear.clear();
        } else {
            results = prev;
            // merge with the previous result set

            if (!this.tlnIdChange.isEmpty()) {//Merge top level node id changes
                var iter = this.tlnIdChange.intIterator();
                while (iter.hasNext()) {
                    int val = iter.nextInt();
                    if (!results.tlnDelta.remove(val ^ (1 << 31))) {//Remove opposite
                        results.tlnDelta.add(val);//Add this if not added
                    }
                }
                this.tlnIdChange.clear();
            }

            if (!this.cleanerIdResetClear.isEmpty()) {//Merge top level node id changes
                var iter = this.cleanerIdResetClear.intIterator();
                while (iter.hasNext()) {
                    int val = iter.nextInt();
                    results.cleanerOperations.remove(val^(1<<31));//Remove opposite
                    results.cleanerOperations.add(val);//Add this
                }
                this.cleanerIdResetClear.clear();
            }

            if (!this.geometryManager.getHeapRemovals().isEmpty()) {//Remove and free all the removed geometry uploads
                var rem = this.geometryManager.getHeapRemovals();
                var iter = rem.intIterator();
                while (iter.hasNext()) {
                    results.geometryUpload.remove(iter.nextInt());
                }
                rem.clear();
            }

            if (!this.geometryManager.getUploads().isEmpty()) {//Add all the new uploads to the result set
                var add = this.geometryManager.getUploads();
                var iter = add.int2ObjectEntrySet().fastIterator();
                while (iter.hasNext()) {
                    var val = iter.next();
                    results.geometryUpload.upload(val.getIntKey(), val.getValue());
                    val.getValue().free();
                }
                add.clear();
            }
        }

        {//This is the same regardless of if is a merge or new result
            //Geometry id metadata updates
            if (!this.geometryManager.getUpdateIds().isEmpty()) {
                var ids = this.geometryManager.getUpdateIds();
                var iter = ids.intIterator();
                while (iter.hasNext()) {
                    int val = iter.nextInt();
                    int scatterAddr = (val<<1)|(1<<31);//Since we write to the second buffer

                    //Geometry buffer is index of 1, so mutate to put it in that location, it is also 32 bytes, so needs to be split into 2 separate scatter writes
                    long ptrA = results.getScatterWritePtr(scatterAddr+0, 1);
                    long ptrB = results.getScatterWritePtr(scatterAddr+1, 0);

                    //Write update data
                    this.geometryManager.writeMetadataSplit(val, ptrA, ptrB);
                }
                ids.clear();
            }

            //Node updates
            if (!this.manager.getNodeUpdates().isEmpty()) {
                var ids = this.manager.getNodeUpdates();
                var iter = ids.intIterator();
                while (iter.hasNext()) {
                    int val = iter.nextInt();
                    //Dont need to modify the write location since we write to buffer 0
                    long ptr = results.getScatterWritePtr(val);
                    //Write updated data
                    this.manager.writeNode(val, ptr);
                }
                ids.clear();
            }
        }

        results.geometrySectionCount = this.geometryManager.getSectionCount();
        results.usedGeometry = this.geometryManager.getGeometryUsedBytes();
        results.currentMaxNodeId = this.manager.getCurrentMaxNodeId();

        this.needsWaitForSync |= results.geometryUpload.currentElemCopyAmount*8L > 2L<<20;//2mb limit per frame
        this.needsWaitForSync |= results.cleanerOperations.size() > 1024;
        this.needsWaitForSync |= results.scatterWriteLocationMap.size() > 4096;
        this.needsWaitForSync |= results.tlnDelta.size() > 10;

        if (!RESULT_HANDLE.compareAndSet(this, null, results)) {
            throw new IllegalArgumentException("Should always have null");
        }
    }

    private IntConsumer tlnAddCallback; private IntConsumer tlnRemoveCallback;
    //Render thread synchronization
    public void tick(GlBuffer nodeBuffer, NodeCleaner cleaner) {//TODO: dont pass nodeBuffer here??, do something else thats better
        var results = (SyncResults)RESULT_HANDLE.getAndSet(this, null);//Acquire the results
        if (results == null) {//There are no new results to process, return
            return;
        }
        // Wake the worker if it's parked waiting for the previous results to
        // drain. Pair with the parkNanos loop in run() — the consumer just
        // cleared the slot, the worker can now publish next-cycle results.
        LockSupport.unpark(this.thread);

        //top level node add/remove
        if (!results.tlnDelta.isEmpty()) {
            var iter = results.tlnDelta.intIterator();
            while (iter.hasNext()) {
                int val = iter.nextInt();
                if ((val&(1<<31))!=0) {//Add node
                    this.tlnAddCallback.accept(val&(-1>>>1));
                } else {
                    this.tlnRemoveCallback.accept(val);
                }
            }
            //Dont need to clear as is not used again
        }

        // Tracks whether multiMemcpy ran, so we can defer its trailing barrier:
        // when scatterWrite runs after it, scatterWrite's leading SSBO barrier
        // already covers multiMemcpy's outputs (they're written to different
        // buffers, but the SSBO barrier is a global write→read fence). Saves
        // one glMemoryBarrier round-trip per tick when both passes fire.
        boolean ranMultiMemcpy = false;

        {//Update basic geometry data
            var store = (BasicSectionGeometryData)this.geometryData;

            store.setSectionCount(results.geometrySectionCount);

            var upload = results.geometryUpload;
            if (!upload.dataUploadPoints.isEmpty()) {
                ((BasicSectionGeometryData)this.geometryData).ensureAccessable(upload.maxElementAccess);
                TimingStatistics.A.start();

                int copies = upload.dataUploadPoints.size();
                int upCopies = UploadStream.alignUpAlloc(copies*16);
                int scratchSize = (int) upload.arena.getSize() * 8;
                int upScratchSize = UploadStream.alignUpAlloc(scratchSize);
                long ptr = UploadStream.INSTANCE.rawUploadAddress(upScratchSize + upCopies);
                UnsafeUtil.memcpy(upload.scratchHeaderBuffer.address, UploadStream.INSTANCE.getBaseAddress() + ptr, copies * 16L);
                UnsafeUtil.memcpy(upload.scratchDataBuffer.address, UploadStream.INSTANCE.getBaseAddress() + ptr + upCopies, scratchSize);
                UploadStream.INSTANCE.commit();//Commit the buffer

                this.multiMemcpy.bind();
                glBindBufferRange(GL_SHADER_STORAGE_BUFFER, 0, UploadStream.INSTANCE.getRawBufferId(), ptr, upCopies);
                glBindBufferRange(GL_SHADER_STORAGE_BUFFER, 1, UploadStream.INSTANCE.getRawBufferId(), ptr+upCopies, upScratchSize);
                glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 2, ((BasicSectionGeometryData) this.geometryData).getGeometryBuffer().id);

                if (copies > 500) {
                    Logger.warn("Large amount of copies, lag will probably happen: " + copies);
                }

                glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
                glDispatchCompute(copies, 1, 1);//Execute the copies
                ranMultiMemcpy = true;
                //Trailing barrier deferred: scatterWrite's leading barrier
                //below covers it when both passes run; the else-if fallback
                //after the scatterWrite block covers the only-multiMemcpy case.

                TimingStatistics.A.stop();
            }
        }

        TimingStatistics.B.start();
        if (!results.scatterWriteLocationMap.isEmpty()) {//Scatter write
            int count = results.scatterWriteLocationMap.size();//Number of writes, not chunks or uvec4 count
            int chunks = (count+3)/4;
            int streamSize = chunks*80;//80 bytes per chunk, it is guaranteed the buffer is big enough
            long ptr = UploadStream.INSTANCE.rawUploadAddress(streamSize);//Internally implicitly aligned alloc
            MemoryUtil.memCopy(results.scatterWriteBuffer.address, UploadStream.INSTANCE.getBaseAddress() + ptr, streamSize);
            UploadStream.INSTANCE.commit();//Commit the buffer

            this.scatterWrite.bind();
            glBindBufferRange(GL_SHADER_STORAGE_BUFFER, 0, UploadStream.INSTANCE.getRawBufferId(), ptr, UploadStream.alignUpAlloc(streamSize));
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, nodeBuffer.id);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 2, ((BasicSectionGeometryData) this.geometryData).getMetadataBuffer().id);
            glUniform1ui(0, count);
            glMemoryBarrier(GL_UNIFORM_BARRIER_BIT|GL_SHADER_STORAGE_BARRIER_BIT);
            glDispatchCompute((count+127)/128, 1, 1);
            glMemoryBarrier(GL_UNIFORM_BARRIER_BIT|GL_SHADER_STORAGE_BARRIER_BIT);
        } else if (ranMultiMemcpy) {
            //Only multiMemcpy ran this tick — emit its deferred trailing
            //barrier so its writes are visible to next-frame consumers.
            glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
        }
        TimingStatistics.B.stop();

        TimingStatistics.C.start();
        if (!results.cleanerOperations.isEmpty()) {
            cleaner.updateIds(results.cleanerOperations);
        }
        TimingStatistics.C.stop();

        this.currentMaxNodeId = results.currentMaxNodeId;
        this.usedGeometryAmount = results.usedGeometry;

        //Insert the result set into the cache
        if (!RESULT_CACHE_1_HANDLE.compareAndSet(this, null, results)) {
            //Failed to insert into result set 1, insert it into result set 2
            if (!RESULT_CACHE_2_HANDLE.compareAndSet(this, null, results)) {
                throw new IllegalStateException("Could not insert result into cache");
            }
        }
    }


    public void setTLNAddRemoveCallbacks(IntConsumer add, IntConsumer remove) {
        this.tlnAddCallback = add;
        this.tlnRemoveCallback = remove;
    }

    private int currentMaxNodeId = 0;
    public int getCurrentMaxNodeId() {
        return this.currentMaxNodeId;
    }

    private long usedGeometryAmount = 0;
    public long getUsedGeometryCapacity() {
        return this.usedGeometryAmount;
    }

    public long getGeometryCapacity() {
        return this.geometryCapacity;
    }


    //==================================================================================================================
    //Incoming events

    // O(1) size counters paired with each unbounded ConcurrentLinkedDeque
    // (ConcurrentLinkedDeque.size() is O(n)). Incremented on submit,
    // decremented after a non-null poll. If a future refactor adds a poll
    // site that forgets to decrement, the counter overestimates queue depth
    // — gives a spurious cap warning, never a correctness bug.
    private final ConcurrentLinkedDeque<MemoryBuffer> requestBatchQueue = new ConcurrentLinkedDeque<>();
    private final AtomicInteger requestBatchQueueSize = new AtomicInteger();
    private final ConcurrentLinkedDeque<WorldSection> childUpdateQueue = new ConcurrentLinkedDeque<>();
    private final AtomicInteger childUpdateQueueSize = new AtomicInteger();
    private final ConcurrentLinkedDeque<BuiltSection> geometryUpdateQueue = new ConcurrentLinkedDeque<>();
    private final AtomicInteger geometryUpdateQueueSize = new AtomicInteger();

    private final ConcurrentLinkedDeque<MemoryBuffer> removeBatchQueue = new ConcurrentLinkedDeque<>();
    private final AtomicInteger removeBatchQueueSize = new AtomicInteger();

    // Visibility-only soft cap. Exceeding this means the worker is falling
    // behind; we warn (rate-limited) so the symptom is loggable without
    // changing producer/consumer semantics. No shed, no block — backpressuring
    // producers would just shift the queue elsewhere.
    private static final int SOFT_QUEUE_CAP = 50_000;
    private final AtomicLong nextQueueCapWarnNanos = new AtomicLong();

    // Worker observability: lets us answer "was the batching delay
    // load-bearing" by inspecting avg work/cycle. Heartbeat log every 5s
    // (deltas) plus addDebug exposure for F3.
    private final AtomicLong cycleCount = new AtomicLong();
    private final AtomicLong totalWorkProcessed = new AtomicLong();
    private long lastHeartbeatNanos = System.nanoTime();
    private long lastHeartbeatCycles = 0;
    private long lastHeartbeatWork = 0;
    private static final long HEARTBEAT_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(5);

    private final StampedLock tlnLock = new StampedLock();
    private final LongOpenHashSet tlnAdd = new LongOpenHashSet();
    private final LongOpenHashSet tlnRem = new LongOpenHashSet();

    private void addWork() {
        if (!this.running) throw new IllegalStateException("Not running");
        if (this.workCounter.getAndIncrement() == 0) {
            LockSupport.unpark(this.thread);
        }
    }

    public void submitRequestBatch(MemoryBuffer batch) {//Only called from render thread
        this.requestBatchQueue.add(batch);
        this.checkQueueCap("requestBatchQueue", this.requestBatchQueueSize.incrementAndGet());
        this.addWork();
    }

    private void submitChildChange(WorldSection section) {
        if (!this.running) {
            return;
        }
        section.acquire();//We must acquire the section before putting in the queue
        this.childUpdateQueue.add(section);
        this.checkQueueCap("childUpdateQueue", this.childUpdateQueueSize.incrementAndGet());
        this.addWork();
    }

    private void submitGeometryResult(BuiltSection geometry) {
        if (!this.running) {
            geometry.free();
            return;
        }
        this.geometryUpdateQueue.add(geometry);
        this.checkQueueCap("geometryUpdateQueue", this.geometryUpdateQueueSize.incrementAndGet());
        this.addWork();
    }

    public void submitRemoveBatch(MemoryBuffer batch) {//Only called from render thread
        this.removeBatchQueue.add(batch);
        this.checkQueueCap("removeBatchQueue", this.removeBatchQueueSize.incrementAndGet());
        this.addWork();
    }

    // Rate-limited soft-cap warning. Once-per-5s across all queues so spam
    // doesn't drown the log if multiple queues are over-cap simultaneously.
    private void checkQueueCap(String queueName, int currentSize) {
        if (currentSize <= SOFT_QUEUE_CAP) return;
        long now = System.nanoTime();
        long next = this.nextQueueCapWarnNanos.get();
        if (now < next) return;
        if (!this.nextQueueCapWarnNanos.compareAndSet(next, now + HEARTBEAT_INTERVAL_NANOS)) return;
        Logger.warn("AsyncNodeManager " + queueName + " over soft cap: size=" + currentSize
                + " (cap=" + SOFT_QUEUE_CAP + ") — worker thread is falling behind");
    }

    public void addTopLevel(long section) {//Only called from render thread
        if (!this.running) throw new IllegalStateException("Not running");
        long stamp = this.tlnLock.writeLock();
        int state = 0;
        if (!this.tlnRem.remove(section)) {
            state += this.tlnAdd.add(section)?1:0;
        } else {
            state -= 1;
        }
        if (state != 0) {
            if (this.workCounter.getAndAdd(state) == 0) {
                LockSupport.unpark(this.thread);
            }
        }
        this.tlnLock.unlockWrite(stamp);
    }

    public void removeTopLevel(long section) {//Only called from render thread
        if (!this.running) throw new IllegalStateException("Not running");
        long stamp = this.tlnLock.writeLock();
        int state = 0;
        if (!this.tlnAdd.remove(section)) {
            state += this.tlnRem.add(section)?1:0;
        } else {
            state -= 1;
        }
        if (state != 0) {
            if (this.workCounter.getAndAdd(state) == 0) {
                LockSupport.unpark(this.thread);
            }
        }
        this.tlnLock.unlockWrite(stamp);
    }

    //==================================================================================================================

    public void start() {
        this.thread.start();
    }

    public void stop() {
        if (!this.running) {
            throw new IllegalStateException();
        }
        this.running = false;
        LockSupport.unpark(this.thread);
        try {
            while (this.thread.isAlive()) {
                LockSupport.unpark(this.thread);
                this.thread.join(1000);
            }
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        while (true) {
            var buffer = this.requestBatchQueue.poll();
            if (buffer == null) break;
            buffer.free();
        }

        while (true) {
            var buffer = this.removeBatchQueue.poll();
            if (buffer == null) break;
            buffer.free();
        }

        while (true) {
            var buffer = this.geometryUpdateQueue.poll();
            if (buffer == null) break;
            buffer.free();
        }

        while (true) {
            var section = this.childUpdateQueue.poll();
            if (section == null) break;
            section.release();
        }

        if (RESULT_HANDLE.get(this) != null) {
            var result = (SyncResults)RESULT_HANDLE.getAndSet(this, null);
            result.geometryUpload.free();
            result.scatterWriteBuffer.free();
        }

        if (RESULT_CACHE_1_HANDLE.get(this) != null) {//Clear cache 1
            var result = (SyncResults)RESULT_CACHE_1_HANDLE.getAndSet(this, null);
            result.geometryUpload.free();
            result.scatterWriteBuffer.free();
        }

        if (RESULT_CACHE_2_HANDLE.get(this) != null) {//Clear cache 2
            var result = (SyncResults)RESULT_CACHE_2_HANDLE.getAndSet(this, null);
            result.geometryUpload.free();
            result.scatterWriteBuffer.free();
        }

        this.scatterWrite.free();
        this.multiMemcpy.free();
        this.geometryCache.free();
    }

    public void addDebug(List<String> debug) {
        debug.add("UC/GC,#N: " + (this.getUsedGeometryCapacity()/(1<<20))+"/"+(this.getGeometryCapacity()/(1<<20)) + "," + (this.geometryData.getSectionCount()));
        long cycles = this.cycleCount.get();
        long work = this.totalWorkProcessed.get();
        long avgWorkPerCycle = (cycles == 0) ? 0 : (work / cycles);
        debug.add("ANM cyc/work/avg: " + cycles + "/" + work + "/" + avgWorkPerCycle
                + " | Q[req/child/geo/rem]: "
                + this.requestBatchQueueSize.get() + "/"
                + this.childUpdateQueueSize.get() + "/"
                + this.geometryUpdateQueueSize.get() + "/"
                + this.removeBatchQueueSize.get());
    }

    // Called at the end of each run() cycle from the single worker thread.
    // Logs a 5s heartbeat with deltas so the log file has a time-series for
    // post-hoc analysis (was B-26-followup's "objectively verify batching
    // removal didn't tank throughput" workflow). Worker-thread-only fields
    // — no synchronization needed.
    private void maybeLogHeartbeat() {
        long now = System.nanoTime();
        if (now - this.lastHeartbeatNanos < HEARTBEAT_INTERVAL_NANOS) return;
        long elapsedNanos = now - this.lastHeartbeatNanos;
        long cycles = this.cycleCount.get();
        long work = this.totalWorkProcessed.get();
        long deltaCycles = cycles - this.lastHeartbeatCycles;
        long deltaWork = work - this.lastHeartbeatWork;
        long avgPerCycle = (deltaCycles == 0) ? 0 : (deltaWork / deltaCycles);
        double elapsedSec = elapsedNanos / 1_000_000_000.0;
        long cyclesPerSec = (long) (deltaCycles / Math.max(elapsedSec, 1e-9));
        long workPerSec = (long) (deltaWork / Math.max(elapsedSec, 1e-9));
        //Gate only the log line, not the bookkeeping below, so that enabling
        //heartbeatLogging mid-session reports a correct 5s delta rather than
        //one catch-up window spanning everything since the last emitted line.
        if (VoxyConfig.CONFIG.heartbeatLogging) {
            Logger.info("AsyncNodeManager heartbeat: cycles/s=" + cyclesPerSec
                    + " work/s=" + workPerSec
                    + " avg work/cycle=" + avgPerCycle
                    + " Q[req/child/geo/rem]="
                    + this.requestBatchQueueSize.get() + "/"
                    + this.childUpdateQueueSize.get() + "/"
                    + this.geometryUpdateQueueSize.get() + "/"
                    + this.removeBatchQueueSize.get());
        }
        this.lastHeartbeatNanos = now;
        this.lastHeartbeatCycles = cycles;
        this.lastHeartbeatWork = work;
    }

    public boolean hasWork() {
        // workCounter only reflects unclaimed submissions under the
        // getAndSet(0) accounting scheme — once the worker claims, the
        // counter is 0 even while it's mid-drain. Check the per-queue sizes
        // too so this returns true while work is in flight.
        return this.workCounter.get() != 0
                || this.requestBatchQueueSize.get() != 0
                || this.childUpdateQueueSize.get() != 0
                || this.geometryUpdateQueueSize.get() != 0
                || this.removeBatchQueueSize.get() != 0
                || RESULT_HANDLE.get(this) != null;
    }

    public void worldEvent(WorldSection section, int flags, int neighborMask) {
        //If there is any change, we need to clear the geometry cache before emitting update
        this.geometryCache.clear(section.key);

        this.router.forwardEvent(section, flags);

        if (neighborMask != 0) {//trigger rebuilds for neighbors
            if ((neighborMask&0b000001)!=0) this.router.triggerRemesh(WorldEngine.getWorldSectionId(section.lvl, section.x, section.y-1, section.z));//-y
            if ((neighborMask&0b000010)!=0) this.router.triggerRemesh(WorldEngine.getWorldSectionId(section.lvl, section.x, section.y+1, section.z));//+y
            if ((neighborMask&0b000100)!=0) this.router.triggerRemesh(WorldEngine.getWorldSectionId(section.lvl, section.x-1, section.y, section.z));//-x
            if ((neighborMask&0b001000)!=0) this.router.triggerRemesh(WorldEngine.getWorldSectionId(section.lvl, section.x+1, section.y, section.z));//+x
            if ((neighborMask&0b010000)!=0) this.router.triggerRemesh(WorldEngine.getWorldSectionId(section.lvl, section.x, section.y, section.z-1));//-z
            if ((neighborMask&0b100000)!=0) this.router.triggerRemesh(WorldEngine.getWorldSectionId(section.lvl, section.x, section.y, section.z+1));//+z
        }
    }

    //Results object, which is to be synced between the render thread and worker thread
    private static final class SyncResults {
        //Contains
        // geometry uploads and id invalidations and the data
        // node ids to invalidate/update and its data
        // top level node ids to add/remove
        // cleaner move and set operations

        //Node id updates + size
        private int currentMaxNodeId;// the id of the ending of the node ids

        //TLN add/rem
        private final IntOpenHashSet tlnDelta = new IntOpenHashSet();

        //Deltas for geometry store
        private int geometrySectionCount;
        private long usedGeometry;
        private final ComputeMemoryCopy geometryUpload = new ComputeMemoryCopy();

        //Gpu geometry downloads



        //Scatter writes for both geometry and node metadata
        private static final long SCATTER_INITIAL_SIZE = 8192L*2;
        //Consecutive quiet cycles required before the buffer is allowed to shrink
        private static final int SHRINK_QUIET_CYCLES = 64;
        private MemoryBuffer scatterWriteBuffer = new MemoryBuffer(SCATTER_INITIAL_SIZE);
        private final Int2IntOpenHashMap scatterWriteLocationMap = new Int2IntOpenHashMap(1024);
        {this.scatterWriteLocationMap.defaultReturnValue(-1);}
        //Shrink hysteresis state, see reset(). Worker-thread-only, no sync needed.
        private long recentPeakBytes = 0;
        private int quietCycles = 0;

        //Cleaner operations
        private final IntOpenHashSet cleanerOperations = new IntOpenHashSet();

        public void reset() {
            int peakUsage = this.scatterWriteLocationMap.size();
            this.cleanerOperations.clear();
            this.scatterWriteLocationMap.clear();
            this.currentMaxNodeId = 0;
            this.tlnDelta.clear();
            this.geometrySectionCount = 0;
            this.usedGeometry = 0;
            this.geometryUpload.reset();

            // Shrink the scatter buffer when a past burst has left it well
            // over-allocated. Without any shrink, all 3 SyncResults retain the
            // session high-water-mark forever (3x amplification). Each map
            // entry consumes ceil(N/4)*80 bytes (5 uvec4 per chunk of 4
            // locations).
            //
            // A single quiet cycle is NOT evidence the burst is over: LOD
            // streaming alternates busy and idle cycles, so collapsing straight
            // back to the floor made every alternation re-pay the whole
            // malloc/memcpy/free growth chain. Hence two changes: require
            // SHRINK_QUIET_CYCLES consecutive quiet cycles, and size the new
            // buffer from the window's observed peak rather than the floor, so
            // the next burst fits without reallocating at all.
            long usedBytes = ((peakUsage + 3L) / 4L) * 80L;
            this.recentPeakBytes = Math.max(this.recentPeakBytes, usedBytes);
            if (usedBytes * 2 < this.scatterWriteBuffer.size) {
                this.quietCycles++;
            } else {
                this.quietCycles = 0;
            }

            if (this.quietCycles >= SHRINK_QUIET_CYCLES) {
                //Target the window peak with 2x headroom, floored and chunk-aligned
                long target = Math.max(SCATTER_INITIAL_SIZE, this.recentPeakBytes * 2);
                target = ((target + 79) / 80) * 80;
                //Only realloc when it reclaims something worthwhile (at least halving)
                if (target * 2 <= this.scatterWriteBuffer.size) {
                    this.scatterWriteBuffer.free();
                    this.scatterWriteBuffer = new MemoryBuffer(target);
                }
                //Restart the window either way, so a stale peak cannot pin the buffer
                this.recentPeakBytes = 0;
                this.quietCycles = 0;
            }
        }

        //Get or create a scatter write address for the given location
        public long getScatterWritePtr(int location) {
            return this.getScatterWritePtr(location, 0);
        }

        //ensureExtra is used to ensure that allocations are "effectivly" in the same memory block (kinda?)
        public long getScatterWritePtr(int location, int ensureExtra) {
            int loc = this.scatterWriteLocationMap.get(location);
            if (loc == -1) {//Location doesnt exist, create it
                this.ensureScatterBufferCapacity(1+ensureExtra);//Ensure can contain capacity for this + extra
                int baseId = this.scatterWriteLocationMap.size();
                int chunkBase = (baseId/4)*5;//Base uvec4 index
                int innerId   = baseId&3;
                MemoryUtil.memPutInt(this.scatterWriteBuffer.address + (chunkBase*16L) + (innerId*4L), location);//Set the write location
                int writeLocation = (chunkBase+1+innerId);//Write location in uvec4
                this.scatterWriteLocationMap.put(location, writeLocation);
                return this.scatterWriteBuffer.address + (writeLocation*16L);
            } else {
                return this.scatterWriteBuffer.address + (16L*loc);
            }
        }

        private void ensureScatterBufferCapacity(int extra) {
            int requiredChunks = ((this.scatterWriteLocationMap.size()+extra)+3)/4;//4 entries in a chunk
            long requiredSize = requiredChunks*5L*16L;//5 uvec4 per chunk, 16 bytes per uvec4
            if (this.scatterWriteBuffer.size <= requiredSize) {//Needs resize
                long newSize = (long) ((this.scatterWriteBuffer.size*1.5) + extra*80L);
                newSize = ((newSize+79)/80)*80;//Ceil to chunk size

                Logger.debug("Expanding scatter update buffer to " + newSize);

                var newBuffer = new MemoryBuffer(newSize);
                this.scatterWriteBuffer.cpyTo(newBuffer.address);
                this.scatterWriteBuffer.free();
                this.scatterWriteBuffer = newBuffer;
            }
        }
    }

    private static class ComputeMemoryCopy {
        public int currentElemCopyAmount;
        public int maxElementAccess;
        private MemoryBuffer scratchHeaderBuffer = new MemoryBuffer(1<<16);
        private MemoryBuffer scratchDataBuffer = new MemoryBuffer(1<<20);

        private final AllocationArena arena = new AllocationArena();
        private final Int2IntOpenHashMap dataUploadPoints = new Int2IntOpenHashMap();//Points to the header index
        {this.dataUploadPoints.defaultReturnValue(-1);}


        public void remove(int point) {
            int header = this.dataUploadPoints.remove(point);
            if (header == -1) {//No upload for point
                return;
            }
            int size = MemoryUtil.memGetInt(this.scratchHeaderBuffer.address + header*16L + 8L);
            this.currentElemCopyAmount -= size;
            //Free the old memory addr from arena
            if (this.arena.free(MemoryUtil.memGetInt(this.scratchHeaderBuffer.address + header*16L)) != size) {
                throw new IllegalStateException("Freed memory not same size as expected");
            }
            if (MemoryUtil.memGetInt(this.scratchHeaderBuffer.address + header*16L + 4L) != point) {
                throw new IllegalStateException("Destination not the same as point");
            }

            //If we were the end upload header, return as we dont need to shuffle
            if (header == this.dataUploadPoints.size()) {
                long A = this.scratchHeaderBuffer.address + header*16L;
                //Zero the memory, for consistancy
                MemoryUtil.memPutLong(A, 0);
                MemoryUtil.memPutLong(A+8, 0);
                return;
            }

            //Else: we need to move the ending upload header from the end to where the freed point was
            int endingPoint = MemoryUtil.memGetInt(this.scratchHeaderBuffer.address + this.dataUploadPoints.size()*16L + 4);
            if (this.dataUploadPoints.get(endingPoint) != this.dataUploadPoints.size()) {
                throw new IllegalStateException("ending header not pointing at end point");
            }

            //Move the end header to the old header location
            long A = this.scratchHeaderBuffer.address + this.dataUploadPoints.size()*16L;
            long B = this.scratchHeaderBuffer.address + header*16L;
            MemoryUtil.memPutLong(B, MemoryUtil.memGetLong(A)); MemoryUtil.memPutLong(A, 0);
            MemoryUtil.memPutLong(B+8, MemoryUtil.memGetLong(A+8)); MemoryUtil.memPutLong(A+8, 0);

            //Update the map
            this.dataUploadPoints.put(endingPoint, header);
        }

        public void upload(int point, MemoryBuffer data) {
            if ((data.size%8)!=0) throw new IllegalStateException("Data must be of size multiple 8");
            int elemSize = (int) (data.size / 8);
            this.maxElementAccess = Math.max(this.maxElementAccess, point + elemSize);
            int header = this.dataUploadPoints.get(point);
            if (header != -1) {
                //If we already have a header location, we just need to reallocate the data
                long headerPtr = this.scratchHeaderBuffer.address + header*16L;
                if (MemoryUtil.memGetInt(headerPtr+4L) != point) {
                    throw new IllegalStateException("Existing destination not the point");
                }
                int pSize = MemoryUtil.memGetInt(headerPtr+8L);//Previous size
                if (pSize == elemSize) {
                    //The data we are replacing is the same size, so just overwrite it, this is the easiest
                    data.cpyTo(this.scratchDataBuffer.address+MemoryUtil.memGetInt(headerPtr)*8L);
                } else {
                    //Dealloc
                    if (this.arena.free(MemoryUtil.memGetInt(headerPtr)) != pSize) {
                        throw new IllegalStateException("Freed allocation not size as expected");
                    }

                    this.currentElemCopyAmount -= pSize;
                    this.currentElemCopyAmount += elemSize;

                    int alloc = this.allocScratchDataPos(elemSize);//New allocation position
                    //Copy data into position
                    data.cpyTo(this.scratchDataBuffer.address+alloc*8L);

                    //Update the header
                    MemoryUtil.memPutInt(headerPtr, alloc);
                    MemoryUtil.memPutInt(headerPtr+8, elemSize);
                }
            } else {
                //We need to create and allocate a new header for the upload
                header = this.dataUploadPoints.size();
                this.dataUploadPoints.put(point, header);

                if (this.scratchHeaderBuffer.size<=header*16L) {
                    //We must resize the header buffer
                    long newSize = Math.max(this.scratchHeaderBuffer.size*2, header*16L);
                    Logger.info("Resizing scratch header buffer to: " + newSize);
                    var newScratch = new MemoryBuffer(newSize);
                    this.scratchHeaderBuffer.cpyTo(newScratch.address);
                    this.scratchHeaderBuffer.free();
                    this.scratchHeaderBuffer = newScratch;
                }

                long headerPtr = this.scratchHeaderBuffer.address + header*16L;//Header resize has happened so this is a stable address

                this.currentElemCopyAmount += elemSize;

                int alloc = this.allocScratchDataPos(elemSize);//New allocation position
                //Copy data into position
                data.cpyTo(this.scratchDataBuffer.address+alloc*8L);

                //Set header data
                MemoryUtil.memPutInt(headerPtr, alloc);
                MemoryUtil.memPutInt(headerPtr+4, point);
                MemoryUtil.memPutInt(headerPtr+8, elemSize);
            }
        }

        //This is done here as it enables easily doing scratch data resizing
        private int allocScratchDataPos(int size) {
            int pos = (int) this.arena.alloc(size);
            if (this.scratchDataBuffer.size <= (pos+size)*8L) {
                //We must resize :cri:
                long newSize = Math.max(this.scratchDataBuffer.size*2, (pos+size)*8L);
                Logger.info("Resizing scratch data buffer to: " + newSize);
                var newScratch = new MemoryBuffer(newSize);
                this.scratchDataBuffer.cpyTo(newScratch.address);
                this.scratchDataBuffer.free();
                this.scratchDataBuffer = newScratch;
            }
            return pos;
        }

        public void reset() {
            this.maxElementAccess = 0;
            this.currentElemCopyAmount = 0;
            this.dataUploadPoints.clear();
            this.arena.reset();
        }

        public void free() {
            this.scratchHeaderBuffer.free(); this.scratchHeaderBuffer = null;
            this.scratchDataBuffer.free(); this.scratchDataBuffer = null;
        }
    }
}
