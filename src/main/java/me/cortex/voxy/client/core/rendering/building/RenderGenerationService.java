package me.cortex.voxy.client.core.rendering.building;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import me.cortex.voxy.client.core.model.IdNotYetComputedException;
import me.cortex.voxy.client.core.model.ModelBakerySubsystem;
import me.cortex.voxy.common.thread.Service;
import me.cortex.voxy.common.thread.ServiceManager;
import me.cortex.voxy.common.util.Pair;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.common.world.other.Mapper;

import java.util.List;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.StampedLock;
import java.util.function.Consumer;

//TODO: Add a render cache


//TODO: to add remove functionallity add a "defunked" variable to the build task and set it to true on remove
// and process accordingly
public class RenderGenerationService {
    private static final int MAX_HOLDING_SECTION_COUNT = 1000;

    public static final AtomicInteger MESH_FAILED_COUNTER = new AtomicInteger();
    private static final AtomicInteger COUNTER = new AtomicInteger();

    // Most recent camera position in level-0 section units, set by the render
    // thread each frame via setCameraSection. Read off-thread by
    // BuildTask.updatePriority to derive a distance bucket so within the same
    // LOD level, sections closer to the camera get higher priority. Volatile
    // for cross-thread visibility; mild staleness (one or two frames) is fine.
    private volatile int cameraSecX = 0;
    private volatile int cameraSecY = 0;
    private volatile int cameraSecZ = 0;

    public void setCameraSection(int x, int y, int z) {
        this.cameraSecX = x;
        this.cameraSecY = y;
        this.cameraSecZ = z;
    }

    // Number of distance buckets; chosen as 32 so 5 bits fits cleanly into the
    // priority value's distance field. Coarser than tile distance, but only
    // needs to break ties across same-level tasks meaningfully.
    private static final int DISTANCE_BUCKETS = 32;
    // Width of one bucket in level-0 sections (32-block units): 4 sections =
    // 128 blocks per bucket, linear. The previous distSq/1024 bucketing put
    // everything within ~1 km in bucket 0, so on a developed-world login the
    // queue freely interleaved a section at the player's feet with one 900
    // blocks out. Linear 128-block buckets enforce nearest-first exactly
    // where it is visible; the last bucket absorbs everything past ~4 km.
    private static final int BUCKET_SECTION_WIDTH = 4;

    private final class BuildTask {
        WorldSection section;
        final long position;
        boolean hasDoneModelRequestInner;
        boolean hasDoneModelRequestOuter;
        int attempts;
        int addin;
        long priority = Long.MIN_VALUE;
        private BuildTask(long position) {
            this.position = position;
        }
        private int distanceBucket() {
            return RenderGenerationService.this.distanceBucketFor(this.position);
        }
        private void updatePriority() {
            int unique = COUNTER.incrementAndGet();
            int lvl = WorldEngine.MAX_LOD_LAYER-WorldEngine.getLevel(this.position);
            lvl = Math.min(lvl, 3);//Make the 2 highest quality have equal priority
            // Layout (smaller priority value polled first):
            //   distance bucket (* 8): close-to-camera is the primary sort.
            //     A fine-detail refinement near the camera now beats a fresh
            //     coarse top-level request at the ring's leading edge -- which
            //     fixes the long-standing case where a cell at vanilla-
            //     render-distance was still at a coarser LOD by the time
            //     vanilla overtook it.
            //   level (0-3): coarser still wins by a hair within the same
            //     distance bucket so initial coverage at a given position is
            //     slightly favoured, but the per-level weight is now 1 vs
            //     distance's 8, so distance dominates between buckets.
            //   attempts (* DISTANCE_BUCKETS * 8): a failed retry
            //     deprioritises across all distance buckets, so retries
            //     don't starve genuinely-pending work.
            //   addin (* 1, low bit): one-shot deprioritisation for distant
            //     model-baking lulls.
            // Shifted left by 32 so the per-task unique counter breaks ties
            // without affecting relative ordering across distinct priorities.
            long base = (this.distanceBucket() * 8L) + lvl + Math.min(this.attempts, 3) * (DISTANCE_BUCKETS * 8L);
            this.priority = ((base * 2 + this.addin) << 32) + Integer.toUnsignedLong(unique);
            this.addin = 0;
        }
    }

    // Camera-distance bucket (0..DISTANCE_BUCKETS-1) for a section position.
    // Shared by BuildTask priorities and the bakery request priority tier.
    private int distanceBucketFor(long position) {
        int level = WorldEngine.getLevel(position);
        long l0x = (long) WorldEngine.getX(position) << level;
        long l0y = (long) WorldEngine.getY(position) << level;
        long l0z = (long) WorldEngine.getZ(position) << level;
        long dx = l0x - this.cameraSecX;
        long dy = l0y - this.cameraSecY;
        long dz = l0z - this.cameraSecZ;
        long distSq = dx*dx + dy*dy + dz*dz;
        int dist = (int) Math.sqrt((double) distSq);
        return Math.min(dist / BUCKET_SECTION_WIDTH, DISTANCE_BUCKETS - 1);
    }

    // Bakery priority tier (0 = nearest) for bake requests issued on behalf
    // of a section: the bakery serves near sections' missing models first,
    // so mesh priority is not overridden by FIFO bake order during login
    // floods (a far section's exotic blocks no longer stall the near field).
    private int bakePriorityTierFor(long position) {
        return this.distanceBucketFor(position) * ModelBakerySubsystem.BAKE_PRIORITY_TIERS / DISTANCE_BUCKETS;
    }

    private final AtomicInteger holdingSectionCount = new AtomicInteger();//Used to limit section holding

    private final AtomicInteger taskQueueCount = new AtomicInteger();
    private final PriorityBlockingQueue<BuildTask> taskQueue = new PriorityBlockingQueue<>(5000, (a,b)-> Long.compareUnsigned(a.priority, b.priority));
    private final StampedLock taskMapLock = new StampedLock();
    private final Long2ObjectOpenHashMap<BuildTask> taskMap = new Long2ObjectOpenHashMap<>(5000);

    private final WorldEngine world;
    private final ModelBakerySubsystem modelBakery;
    private Consumer<BuiltSection> resultConsumer;

    private final Service service;

    public RenderGenerationService(WorldEngine world, ModelBakerySubsystem modelBakery, ServiceManager sm) {
        this.world = world;
        this.modelBakery = modelBakery;

        this.service = sm.createService(()->{
            //Thread local instance of the factory
            var factory = new RenderDataFactory(this.world, this.modelBakery.factory);
            IntOpenHashSet seenMissed = new IntOpenHashSet(128);
            return new Pair<>(() -> {
                this.processJob(factory, seenMissed);
            }, factory::free);
        }, 10, "Section mesh generation service", ()->{
            int modelBakeQueueCount = modelBakery.getProcessingCount();
            if (modelBakeQueueCount>1000) return false;//Pause mesh gen if there is alot of model baking happening
            return modelBakery.getProcessingCount()<400||RenderGenerationService.MESH_FAILED_COUNTER.get()<500;
        });
    }

    public void setResultConsumer(Consumer<BuiltSection> consumer) {
        this.resultConsumer = consumer;
    }

    //NOTE: the biomes are always fully populated/kept up to date

    //Asks the Model system to bake all blocks that currently dont have a model
    private void computeAndRequestRequiredModels(IntOpenHashSet seenMissedIds, int bitMsk, long[] auxData, int bakeTier) {
        final var factory = this.modelBakery.factory;
        for (int i = 0; i < 6; i++) {
            if ((bitMsk&(1<<i))==0) continue;
            for (int j = 0; j < 32*32; j++) {
                int block = Mapper.getBlockId(auxData[j+(i*32*32)]);
                if (block != 0 && !factory.hasModelForBlockId(block)) {
                    if (seenMissedIds.add(block)) {
                        this.modelBakery.requestBlockBake(block, bakeTier);
                    }
                }
            }
        }
    }

    private void computeAndRequestRequiredModels(IntOpenHashSet seenMissedIds, WorldSection section, int bakeTier) {
        //Know this is... very much not safe, however it reduces allocation rates and other garbage, am sure its "fine"
        final var factory = this.modelBakery.factory;
        for (long state : section._unsafeGetRawDataArray()) {
            int block = Mapper.getBlockId(state);
            if (block != 0 && !factory.hasModelForBlockId(block)) {
                if (seenMissedIds.add(block)) {
                    this.modelBakery.requestBlockBake(block, bakeTier);
                }
            }
        }
    }

    private WorldSection acquireSection(long pos) {
        return this.world.acquireIfExists(pos);
    }

    private static boolean putTaskFirst(long pos) {
        //Level 3 or 4
        return WorldEngine.getLevel(pos) > 2;
    }

    //TODO: add a generated render data cache
    private void processJob(RenderDataFactory factory, IntOpenHashSet seenMissedIds) {
        BuildTask task = this.taskQueue.poll();
        this.taskQueueCount.decrementAndGet();

        //long time = BuiltSection.getTime();
        boolean shouldFreeSection = true;

        WorldSection section;
        if (task.section == null) {
            section = this.acquireSection(task.position);
        } else {
            section = task.section;
        }


        {//Remove the task from the map, this is done before we check for null sections as well the task map needs to be correct
            long stamp = this.taskMapLock.writeLock();
            var rtask = this.taskMap.remove(task.position);
            if (rtask != task) {
                this.taskMapLock.unlockWrite(stamp);
                throw new IllegalStateException();
            }
            this.taskMapLock.unlockWrite(stamp);
        }

        if (section == null) {
            if (this.resultConsumer != null) {
                this.resultConsumer.accept(BuiltSection.empty(task.position));
            }
            return;
        }
        section.assertNotFree();
        BuiltSection mesh = null;


        try {
            mesh = factory.generateMesh(section);
        } catch (IdNotYetComputedException e) {
            {
                long stamp = this.taskMapLock.writeLock();
                BuildTask other = this.taskMap.putIfAbsent(task.position, task);
                this.taskMapLock.unlockWrite(stamp);

                if (other != null) {//Weve been replaced
                    //Request the block
                    if (e.isIdBlockId) {
                        //TODO: maybe move this to _after_ task as been readded to queue??
                        if (!this.modelBakery.factory.hasModelForBlockId(e.id)) {
                            if (seenMissedIds.add(e.id)) {
                                this.modelBakery.requestBlockBake(e.id, this.bakePriorityTierFor(task.position));
                            }
                        }
                    }
                    //Exchange info
                    if (task.hasDoneModelRequestInner) {
                        other.hasDoneModelRequestInner = true;
                    }
                    if (task.hasDoneModelRequestOuter) {
                        other.hasDoneModelRequestOuter = true;
                    }
                    if (task.section != null) {
                        this.holdingSectionCount.decrementAndGet();
                    }
                    task.section = null;
                    shouldFreeSection = true;
                    task = null;
                }
            }
            if (task != null) {
                //This is our task
                int bakeTier = this.bakePriorityTierFor(task.position);

                //Request the block
                if (e.isIdBlockId) {
                    //TODO: maybe move this to _after_ task as been readded to queue??
                    if (!this.modelBakery.factory.hasModelForBlockId(e.id)) {
                        if (seenMissedIds.add(e.id)) {
                            this.modelBakery.requestBlockBake(e.id, bakeTier);
                        }
                    }
                }

                if (task.hasDoneModelRequestOuter || task.hasDoneModelRequestInner) {
                    MESH_FAILED_COUNTER.incrementAndGet();
                }

                if (task.hasDoneModelRequestInner && task.hasDoneModelRequestOuter) {
                    task.attempts++;
                } else {
                    if (task.hasDoneModelRequestInner) {
                        task.attempts++;//This is because it can be baking and just model thing isnt keeping up
                    }

                    if (!task.hasDoneModelRequestInner) {
                        //The reason for the extra id parameter is that we explicitly add/check against the exception id due to e.g. requesting accross a chunk boarder wont be captured in the request
                        if (e.auxData == null)//the null check this is because for it to be, the inner must already be computed
                            this.computeAndRequestRequiredModels(seenMissedIds, section, bakeTier);
                        task.hasDoneModelRequestInner = true;
                    }
                    //If this happens... aahaha painnnn
                    if (task.hasDoneModelRequestOuter) {
                        task.attempts++;
                    }

                    if ((!task.hasDoneModelRequestOuter) && e.auxData != null) {
                        this.computeAndRequestRequiredModels(seenMissedIds, e.auxBitMsk, e.auxData, bakeTier);
                        task.hasDoneModelRequestOuter = true;
                    }

                    task.addin = WorldEngine.getLevel(task.position)>2?1:0;//Single time addin which gives the models time to bake before the task executes
                }

                //Keep the lock on the section, and attach it to the task, this prevents needing to re-aquire it later
                if (task.section == null) {
                    if (this.holdingSectionCount.get() < MAX_HOLDING_SECTION_COUNT) {
                        this.holdingSectionCount.incrementAndGet();
                        task.section = section;
                        shouldFreeSection = false;
                    }
                } else {
                    shouldFreeSection = false;
                }

                task.updatePriority();
                this.taskQueue.add(task);
                this.taskQueueCount.incrementAndGet();

                if (this.service.isLive()) {//Only execute if were not dead
                    this.service.execute();//Since we put in queue, release permit
                }
            }
        }

        if (shouldFreeSection) {
            if (task != null && task.section != null) {
                this.holdingSectionCount.decrementAndGet();
            }
            section.release();
        }

        if (mesh != null) {//If the mesh is null it means it didnt finish, so dont submit
            if (this.resultConsumer != null) {
                this.resultConsumer.accept(mesh);
            } else {
                mesh.free();
            }
        }
    }


    public void enqueueTask(long pos) {
        if (!this.service.isLive()) {
            return;
        }
        boolean[] isOurs = new boolean[1];
        long stamp = this.taskMapLock.writeLock();
        BuildTask task = this.taskMap.computeIfAbsent(pos, p->{
                isOurs[0] = true;
                return new BuildTask(p);
            });
        this.taskMapLock.unlockWrite(stamp);

        if (isOurs[0]) {//If its not ours we dont care about it
            //Set priority and insert into queue and execute
            task.updatePriority();
            this.taskQueue.add(task);
            this.taskQueueCount.incrementAndGet();
            this.service.execute();
        }
    }

    /*
    public void enqueueTask(int lvl, int x, int y, int z) {
        this.enqueueTask(WorldEngine.getWorldSectionId(lvl, x, y, z));
    }
    */

    public void shutdown() {
        //Steal and free as much work as possible
        while (this.service.numJobs() != 0) {
            int i = this.service.drain();
            if (i == 0) break;
            {
                long stamp = this.taskMapLock.writeLock();
                for (int j = 0; j < i; j++) {
                    var task = this.taskQueue.remove();
                    if (task.section != null) {
                        task.section.release();
                        this.holdingSectionCount.decrementAndGet();
                    }
                    if (this.taskMap.remove(task.position) != task) {
                        throw new IllegalStateException();
                    }
                }
                this.taskMapLock.unlockWrite(stamp);
                this.taskQueueCount.addAndGet(-i);
            }
        }

        //Shutdown the threads
        this.service.shutdown();

        //Cleanup any remaining data
        while (!this.taskQueue.isEmpty()) {
            var task = this.taskQueue.remove();
            this.taskQueueCount.decrementAndGet();
            if (task.section != null) {
                task.section.release();
                this.holdingSectionCount.decrementAndGet();
            }

            long stamp = this.taskMapLock.writeLock();
            if (this.taskMap.remove(task.position) != task) {
                throw new IllegalStateException();
            }
            this.taskMapLock.unlockWrite(stamp);
        }
        if (this.taskQueueCount.get() != 0) {
            throw new IllegalStateException();
        }
    }

    private long lastChangedTime = 0;
    public void addDebugData(List<String> debug) {
        if (System.currentTimeMillis()-this.lastChangedTime > 100) {
            MESH_FAILED_COUNTER.set(0);
            this.lastChangedTime = System.currentTimeMillis();
        }
        debug.add("RSSQ/TFC: " + this.taskQueueCount.get() + "/" + MESH_FAILED_COUNTER.get());//render section service queue, Task Fail Counter

    }

    public int getTaskCount() {
        return this.taskQueueCount.get();
    }
}
