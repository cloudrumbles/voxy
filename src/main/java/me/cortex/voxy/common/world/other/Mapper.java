package me.cortex.voxy.common.world.other;

import com.mojang.serialization.Dynamic;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.config.IMappingStorage;
import me.cortex.voxy.common.util.Pair;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.datafix.fixes.References;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.lwjgl.system.MemoryUtil;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;


//There are independent mappings for biome and block states, these get combined in the shader and allow for more
// variaty of things
public class Mapper {
    private static final int BLOCK_STATE_TYPE = 1;
    private static final int BIOME_TYPE = 2;

    private final IMappingStorage storage;
    public static final long UNKNOWN_MAPPING = -1;
    public static final long AIR = 0;

    // id -> entry tables are copy-on-write arrays: mutated ONLY under the
    // respective lock (grow-by-copy then volatile publish), read lock-free
    // from hot paths (Mipper opacity loop, mesh gen). The previous
    // ObjectArrayList was read lock-free while add() could grow the backing
    // array under the lock — a concurrent reader could observe a stale or
    // partially-copied backing array (the author's old TODO acknowledged
    // this). With COW, readers see an immutable fully-published snapshot.
    private final ReentrantLock blockLock = new ReentrantLock();
    private final ConcurrentHashMap<BlockState, StateEntry> block2stateEntry = new ConcurrentHashMap<>(2000,0.75f, 10);
    private volatile StateEntry[] blockId2stateEntry = new StateEntry[0];


    private final ReentrantLock biomeLock = new ReentrantLock();
    private final ConcurrentHashMap<String, BiomeEntry> biome2biomeEntry = new ConcurrentHashMap<>(2000,0.75f, 10);
    private volatile BiomeEntry[] biomeId2biomeEntry = new BiomeEntry[0];

    private Consumer<StateEntry> newStateCallback;
    private Consumer<BiomeEntry> newBiomeCallback;

    // Coalesces the WAL fsync that follows each id-mapping write. The
    // crash-consistency invariant is that a mapping must be durable before
    // any SECTION data referencing its id is durable; mappings and sections
    // share one RocksDB WAL, and WAL recovery replays a strict prefix, so
    // write ORDER alone guarantees the invariant — the fsync only bounds
    // how much tail can be lost (mapping + dependent sections are then lost
    // TOGETHER, which is consistent). Fsyncing per registration made
    // first-join-to-a-modpack bursts serialize hundreds of fsyncs under
    // blockLock; coalescing to one per interval keeps a durability
    // heartbeat without the per-id stall.
    private static final long MAPPING_FLUSH_INTERVAL_NS = 100_000_000L;
    private final java.util.concurrent.atomic.AtomicLong lastMappingFlush = new java.util.concurrent.atomic.AtomicLong(Long.MIN_VALUE);

    private void flushMappingsCoalesced() {
        long now = System.nanoTime();
        long last = this.lastMappingFlush.get();
        if (now - last >= MAPPING_FLUSH_INTERVAL_NS && this.lastMappingFlush.compareAndSet(last, now)) {
            this.storage.flush();
        }
    }
    public Mapper(IMappingStorage storage) {
        this.storage = storage;
        //Insert air since its a special entry (index 0)
        var airEntry = new StateEntry(0, Blocks.AIR.defaultBlockState());
        this.block2stateEntry.put(airEntry.state, airEntry);
        this.blockId2stateEntry = new StateEntry[] { airEntry };

        this.loadFromStorage();
    }


    public static boolean isAir(long id) {
        //Note: air can mean void, cave or normal air, as the block state is remapped during ingesting
        return (id&(((1L<<20)-1)<<27)) == 0;
    }

    public static int getBlockId(long id) {
        return (int) ((id>>27)&((1<<20)-1));
    }

    public static int getBiomeId(long id) {
        return (int) ((id>>47)&0x1FF);
    }

    public static int getLightId(long id) {
        return (int) ((id>>56)&0xFF);
    }

    public static long withLight(long id, int light) {
        return (id&(~(0xFFL<<56)))|(Integer.toUnsignedLong(light&0xFF)<<56);
    }

    public static long withBlockBiome(long id, int block, int biome) {
        return (id&(0xFFL<<56))|(Integer.toUnsignedLong(block)<<27)|(Integer.toUnsignedLong(biome)<<47);
    }

    public static long airWithLight(int light) {
        return Integer.toUnsignedLong(light&0xFF)<<56;
    }

    public void setStateCallback(Consumer<StateEntry> stateCallback) {
        this.newStateCallback = stateCallback;
    }

    public void setBiomeCallback(Consumer<BiomeEntry> biomeCallback) {
        this.newBiomeCallback = biomeCallback;
    }

    private void loadFromStorage() {
        //TODO: FIXME: have/store the minecraft version the mappings are from (the data version)
        // SharedConstants.getGameVersion().dataVersion().id()
        // then use this to create an update path instead

        var mappings = this.storage.getIdMappingsData();
        List<StateEntry> sentries = new ArrayList<>();
        List<BiomeEntry> bentries = new ArrayList<>();
        List<Pair<byte[], Integer>> sentryErrors = new ArrayList<>();

        boolean[] forceResave = new boolean[1];
        for (var entry : mappings.int2ObjectEntrySet()) {
            int entryType = entry.getIntKey()>>>30;
            int id = entry.getIntKey() & ((1<<30)-1);
            if (entryType == BLOCK_STATE_TYPE) {
                var sentry = StateEntry.deserialize(id, entry.getValue(), forceResave);
                if (sentry.state.isAir()) {
                    Logger.error("Deserialization was air, removed block");
                    sentryErrors.add(new Pair<>(entry.getValue(), id));
                    continue;
                }
                sentries.add(sentry);
                var oldEntry = this.block2stateEntry.putIfAbsent(sentry.state, sentry);
                if (oldEntry != null) {
                    //forceResave[0] |= true;
                    Logger.warn("Multiple mappings for blockstate, using old state, expect things to possibly go really badly. " + oldEntry.id + ":" + sentry.id + ":" + sentry.state );
                }
            } else if (entryType == BIOME_TYPE) {
                var bentry = BiomeEntry.deserialize(id, entry.getValue());
                bentries.add(bentry);
                if (this.biome2biomeEntry.put(bentry.biome, bentry) != null) {
                    throw new IllegalStateException("Multiple mappings for biome entry");
                }
            } else {
                throw new IllegalStateException("Unknown entryType");
            }
        }

        if (!sentryErrors.isEmpty()) {
            forceResave[0] |= true;
            // Deterministic tombstone for undecodable persisted states. The
            // previous behaviour substituted a RANDOM registry state per
            // corrupted id — silent, different every launch, and capable of
            // landing on anything (light-emitting, animated, ...). Magenta
            // concrete is stable, visually unmistakable as "data error", and
            // logged. Deliberately NOT inserted into block2stateEntry: a
            // genuine magenta-concrete registration must still get its own
            // id, and a tombstone must never satisfy a state lookup. The
            // corrupt bytes stay on disk, so this re-detects (and re-logs)
            // each launch rather than silently laundering the corruption.
            var tombstone = Blocks.MAGENTA_CONCRETE.defaultBlockState();
            Logger.error(sentryErrors.size() + " block-state id mapping(s) failed to deserialize;"
                    + " their ids will render as magenta concrete. Voxel data referencing them was"
                    + " created with mods or versions that can no longer be decoded.");
            for (var error : sentryErrors) {
                sentries.add(new StateEntry(error.right(), tombstone));
            }
        }

        //Insert into the arrays. Bulk-build then publish once — constructor
        //context, no concurrent readers yet, and avoids O(n^2) grow-by-copy.
        {
            var blockTable = new ArrayList<StateEntry>(sentries.size() + 1);
            blockTable.addAll(List.of(this.blockId2stateEntry)); // air entry
            sentries.stream().sorted(Comparator.comparing(a->a.id)).forEach(entry -> {
                if (blockTable.size() != entry.id) {
                    throw new IllegalStateException("Block entry not ordered");
                }
                blockTable.add(entry);
            });
            this.blockId2stateEntry = blockTable.toArray(new StateEntry[0]);

            var biomeTable = new ArrayList<BiomeEntry>(bentries.size());
            bentries.stream().sorted(Comparator.comparing(a->a.id)).forEach(entry -> {
                if (biomeTable.size() != entry.id) {
                    throw new IllegalStateException("Biome entry not ordered. got " + entry.biome + " with id " + entry.id + " expected id " + biomeTable.size());
                }
                biomeTable.add(entry);
            });
            this.biomeId2biomeEntry = biomeTable.toArray(new BiomeEntry[0]);
        }

        if (forceResave[0]) {
            Logger.warn("Forced state resave triggered");
            this.forceResaveStates();
        }
    }

    public final int getBlockStateCount() {
        return this.blockId2stateEntry.length;
    }

    private StateEntry registerNewBlockState(BlockState state) {
        StateEntry entry;
        this.blockLock.lock();
        try {
            entry = this.block2stateEntry.get(state);
            if (entry != null) return entry;

            entry = new StateEntry(this.blockId2stateEntry.length, state);

            // WRITE the mapping to storage BEFORE making the entry visible.
            // The WAL write (putIdMapping) precedes both the in-memory
            // publish and, transitively, any section save referencing the
            // id — and WAL recovery replays a strict prefix, so no durable
            // section can ever reference an id whose mapping was lost. The
            // fsync itself is coalesced (see flushMappingsCoalesced); it
            // bounds tail loss, not ordering.
            byte[] serialized = entry.serialize();
            ByteBuffer buffer = MemoryUtil.memAlloc(serialized.length);
            buffer.put(serialized);
            buffer.rewind();
            this.storage.putIdMapping(entry.id | (BLOCK_STATE_TYPE<<30), buffer);
            MemoryUtil.memFree(buffer);
            this.flushMappingsCoalesced();

            var oldTable = this.blockId2stateEntry;
            var newTable = java.util.Arrays.copyOf(oldTable, oldTable.length + 1);
            newTable[oldTable.length] = entry;
            this.blockId2stateEntry = newTable;
            this.block2stateEntry.put(state, entry);
        } finally {
            this.blockLock.unlock();
        }

        // Callback fires outside the lock — preserves the previous lock-
        // holding profile and avoids stalling other registrations on
        // downstream work (e.g. bakery requests).
        if (this.newStateCallback!=null) this.newStateCallback.accept(entry);
        return entry;
    }

    private BiomeEntry registerNewBiome(String biome) {
        BiomeEntry entry;
        this.biomeLock.lock();
        try {
            entry = this.biome2biomeEntry.get(biome);
            if (entry != null) return entry;

            entry = new BiomeEntry(this.biomeId2biomeEntry.length, biome);

            // See registerNewBlockState for the rationale: persist before
            // publishing the in-memory entry, so a crash window cannot leave
            // voxel data on disk referencing a biome id whose mapping was
            // never durably written.
            byte[] serialized = entry.serialize();
            ByteBuffer buffer = MemoryUtil.memAlloc(serialized.length);
            buffer.put(serialized);
            buffer.rewind();
            this.storage.putIdMapping(entry.id | (BIOME_TYPE<<30), buffer);
            MemoryUtil.memFree(buffer);
            this.flushMappingsCoalesced();

            var oldTable = this.biomeId2biomeEntry;
            var newTable = java.util.Arrays.copyOf(oldTable, oldTable.length + 1);
            newTable[oldTable.length] = entry;
            this.biomeId2biomeEntry = newTable;
            this.biome2biomeEntry.put(biome, entry);
        } finally {
            this.biomeLock.unlock();
        }

        if (this.newBiomeCallback!=null) this.newBiomeCallback.accept(entry);
        return entry;
    }


    //TODO:FIXME: IS VERY SLOW NEED TO MAKE IT LOCK FREE, or at minimum use a concurrent map
    public long getBaseId(byte light, BlockState state, Holder<Biome> biome) {
        if (state.isAir()) return Byte.toUnsignedLong(light) <<56;//Special case and fast return for air, dont care about the biome
        return composeMappingId(light, this.getIdForBlockState(state), this.getIdForBiome(biome));
    }

    public BlockState getBlockStateFromBlockId(int blockId) {
        // Synthetic multi-cell slice ids resolve to their SOURCE block's state. The
        // single comparison is on the always-taken real-id branch in steady state.
        if (MultiCellSliceRegistry.isSynthetic(blockId)) {
            int src = MultiCellSliceRegistry.INSTANCE.getSlice(blockId).sourceBlockId();
            return this.blockId2stateEntry[src].state;
        }
        return this.blockId2stateEntry[blockId].state;
    }

    public int getIdForBlockState(BlockState state) {
        if (state.isAir()) {
            return 0;
        }
        var mapping = this.block2stateEntry.get(state);
        if (mapping == null) {
            mapping = this.registerNewBlockState(state);
        }
        return mapping.id;
    }

    public int getBlockStateOpacity(long mappingId) {
        return this.getBlockStateOpacity(getBlockId(mappingId));
    }

    public int getBlockStateOpacity(int blockId) {
        // Synthetic slices inherit their source block's opacity (used by the mip loop).
        if (MultiCellSliceRegistry.isSynthetic(blockId)) {
            int src = MultiCellSliceRegistry.INSTANCE.getSlice(blockId).sourceBlockId();
            return this.blockId2stateEntry[src].opacity;
        }
        return this.blockId2stateEntry[blockId].opacity;
    }

    public int getIdForBiome(Holder<Biome> biome) {
        String biomeId = biome.unwrapKey().get().location().toString();
        var entry = this.biome2biomeEntry.get(biomeId);
        if (entry == null) {
            entry = this.registerNewBiome(biomeId);
        }
        return entry.id;
    }

    public static long composeMappingId(byte light, int blockId, int biomeId) {
        if (blockId == AIR) {//Dont care about biome for air
            return Byte.toUnsignedLong(light)<<56;
        }
        return (Byte.toUnsignedLong(light)<<56)|(Integer.toUnsignedLong(biomeId) << 47)|(Integer.toUnsignedLong(blockId)<<27);
    }

    // COW table: a volatile read gives an immutable snapshot; clone so the
    // caller can't alias the live table. id == index is enforced at insert.
    public StateEntry[] getStateEntries() {
        return this.blockId2stateEntry.clone();
    }

    public BiomeEntry[] getBiomeEntries() {
        return this.biomeId2biomeEntry.clone();
    }

    public void forceResaveStates() {
        var blocks = new ArrayList<>(this.block2stateEntry.values());
        var biomes = new ArrayList<>(this.biome2biomeEntry.values());


        for (var entry : blocks) {
            if (entry.state.isAir() && entry.id == 0) {
                continue;
            }
            if (this.blockId2stateEntry[entry.id] != entry) {
                throw new IllegalStateException("State Id NOT THE SAME, very critically bad. entry: " + entry.id);
            }
            byte[] serialized = entry.serialize();
            ByteBuffer buffer = MemoryUtil.memAlloc(serialized.length);
            buffer.put(serialized);
            buffer.rewind();
            this.storage.putIdMapping(entry.id | (BLOCK_STATE_TYPE<<30), buffer);
            MemoryUtil.memFree(buffer);
        }

        for (var entry : biomes) {
            if (this.biomeId2biomeEntry[entry.id] != entry) {
                throw new IllegalStateException("Biome Id NOT THE SAME, very critically bad");
            }

            byte[] serialized = entry.serialize();
            ByteBuffer buffer = MemoryUtil.memAlloc(serialized.length);
            buffer.put(serialized);
            buffer.rewind();
            this.storage.putIdMapping(entry.id | (BIOME_TYPE<<30), buffer);
            MemoryUtil.memFree(buffer);
        }

        this.storage.flush();
    }

    public void close() {

    }


    public static final class StateEntry {
        public final int id;
        public final BlockState state;
        public final int opacity;
        public StateEntry(int id, BlockState state) {
            this.id = id;
            this.state = state;
            //Override opacity of leaves to be solid
            if (state.getBlock() instanceof LeavesBlock) {
                this.opacity = 15;
            } else {
                this.opacity = state.getLightBlock(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, net.minecraft.core.BlockPos.ZERO);
            }
        }

        public byte[] serialize() {
            try {
                var serialized = new CompoundTag();
                serialized.putInt("id", this.id);
                serialized.put("block_state", BlockState.CODEC.encodeStart(NbtOps.INSTANCE, this.state).result().get());
                var out = new ByteArrayOutputStream();
                NbtIo.writeCompressed(serialized, out);
                return out.toByteArray();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        public static StateEntry deserialize(int id, byte[] data, boolean[] forceResave) {
            try {
                var compound = NbtIo.readCompressed(new ByteArrayInputStream(data));
                if (!compound.contains("id") || compound.getInt("id") != id) {
                    throw new IllegalStateException("Encoded id != expected id");
                }
                var bsc = compound.getCompound("block_state");
                var state = BlockState.CODEC.parse(NbtOps.INSTANCE, bsc);
                if (!state.result().isPresent()) {
                    Logger.info("Could not decode blockstate, attempting fixes, error: "+ state.error().get().message());
                    bsc = (CompoundTag) DataFixers.getDataFixer().update(References.BLOCK_STATE, new Dynamic<>(NbtOps.INSTANCE,bsc),0, SharedConstants.getCurrentVersion().getDataVersion().getVersion()).getValue();
                    state = BlockState.CODEC.parse(NbtOps.INSTANCE, bsc);
                    if (!state.result().isPresent()) {
                        Logger.error("Could not decode blockstate setting to air. id:" + id + " error: " + state.error().get().message());
                        return new StateEntry(id, Blocks.AIR.defaultBlockState());
                    } else {
                        Logger.info("Fixed blockstate to: " + state.result().orElseThrow());
                        forceResave[0] |= true;
                        return new StateEntry(id, state.result().orElseThrow());
                    }
                } else {
                    return new StateEntry(id, state.result().orElseThrow());
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    public static final class BiomeEntry {
        public final int id;
        public final String biome;

        public BiomeEntry(int id, String biome) {
            this.id = id;
            this.biome = biome;
        }

        public byte[] serialize() {
            try {
                var serialized = new CompoundTag();
                serialized.putInt("id", this.id);
                serialized.putString("biome_id", this.biome);
                var out = new ByteArrayOutputStream();
                NbtIo.writeCompressed(serialized, out);
                return out.toByteArray();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        public static BiomeEntry deserialize(int id, byte[] data) {
            try {
                var compound = NbtIo.readCompressed(new ByteArrayInputStream(data));
                if (!compound.contains("id") || compound.getInt("id") != id) {
                    throw new IllegalStateException("Encoded id != expected id");
                }
                String biome = compound.getString("biome_id");
                return new BiomeEntry(id, biome);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
