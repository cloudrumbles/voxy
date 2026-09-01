package me.cortex.voxy.common.voxelization;

import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import me.cortex.voxy.common.compat.VoxyStateProxyRegistry;
import me.cortex.voxy.common.world.other.Mapper;
import me.cortex.voxy.common.world.other.Mipper;
import net.minecraft.core.Holder;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.util.ZeroBitStorage;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.GlobalPalette;
import net.minecraft.world.level.chunk.HashMapPalette;
import net.minecraft.world.level.chunk.LinearPalette;
import net.minecraft.world.level.chunk.Palette;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.SingleValuePalette;
import java.util.WeakHashMap;

public class WorldConversionFactory {

    // Lithium-style palette detection. Lithium is Fabric-only, but its Forge fork Radium ships the
    // same LithiumHashPalette class under the original me.jellysquid package; newer upstream Lithium
    // builds use net.caffeinemc. Look up the class reflectively so we work with either without a
    // compile-time dependency on the mod.
    private static final Class<?> LITHIUM_PALETTE_CLASS;
    static {
        Class<?> cls = null;
        for (String name : new String[] {
                "me.jellysquid.mods.lithium.common.world.chunk.LithiumHashPalette",
                "net.caffeinemc.mods.lithium.common.world.chunk.LithiumHashPalette"
        }) {
            try {
                cls = Class.forName(name);
                break;
            } catch (ClassNotFoundException ignored) {}
        }
        LITHIUM_PALETTE_CLASS = cls;
    }
    private static final boolean LITHIUM_INSTALLED = LITHIUM_PALETTE_CLASS != null;

    private static final class Cache {
        private final int[] biomeCache = new int[4*4*4];
        private final WeakHashMap<Mapper, Reference2IntOpenHashMap<BlockState>> localMapping = new WeakHashMap<>();
        // Identity cache Holder<Biome> -> voxy biome id, mirroring localMapping
        // for BlockState. Mapper.getIdForBiome builds a fresh
        // ResourceLocation.toString() per call to key its map; without this
        // cache that is 64 String allocations per 16^3 section on the
        // ingest/import hot path. Holder.Reference instances are canonical per
        // registry entry, so reference equality is a safe cache key (a
        // non-canonical holder merely adds a duplicate entry).
        private final WeakHashMap<Mapper, Reference2IntOpenHashMap<Holder<Biome>>> localBiomeMapping = new WeakHashMap<>();
        private int[] paletteCache = new int[1024];
        private Reference2IntOpenHashMap<BlockState> getLocalMapping(Mapper mapper) {
            return this.localMapping.computeIfAbsent(mapper, (a_)->new Reference2IntOpenHashMap<>());
        }
        private Reference2IntOpenHashMap<Holder<Biome>> getLocalBiomeMapping(Mapper mapper) {
            return this.localBiomeMapping.computeIfAbsent(mapper, (a_) -> {
                var map = new Reference2IntOpenHashMap<Holder<Biome>>();
                map.defaultReturnValue(-1);
                return map;
            });
        }
        private int[] getPaletteCache(int size) {
            if (this.paletteCache.length < size) {
                this.paletteCache = new int[size];
            }
            return this.paletteCache;
        }
    }

    //TODO: create a mapping for world/mapper -> local mapping
    private static final ThreadLocal<Cache> THREAD_LOCAL = ThreadLocal.withInitial(Cache::new);

    // Look up the voxy block ID for `state`, applying the registered state-proxy
    // resolver on cache miss. The blockCache is keyed by the ORIGINAL state from
    // the palette (so cache hits skip the proxy resolve entirely); the stored
    // value is the voxy ID of the proxied state. Several original states may
    // map to the same proxied state — that's fine, each gets its own cache
    // entry pointing at the shared voxy ID.
    private static int resolveStateToBlockId(BlockState state, Reference2IntOpenHashMap<BlockState> blockCache, Mapper mapper) {
        int blockId = blockCache.getOrDefault(state, -1);
        if (blockId == -1) {
            BlockState proxied = VoxyStateProxyRegistry.INSTANCE.resolve(state);
            blockId = mapper.getIdForBlockState(proxied);
            blockCache.put(state, blockId);
        }
        return blockId;
    }

    private static boolean setupLithiumLocalPallet(Palette<BlockState> vp, Reference2IntOpenHashMap<BlockState> blockCache, Mapper mapper, int[] pc)  {
        if (LITHIUM_PALETTE_CLASS != null && LITHIUM_PALETTE_CLASS.isInstance(vp)) {
            for (int i = 0; i < vp.getSize(); i++) {
                BlockState state = null;
                int blockId = -1;
                try { state = vp.valueFor(i); } catch (Exception e) {}
                if (state != null) {
                    blockId = resolveStateToBlockId(state, blockCache, mapper);
                }
                pc[i] = blockId;
            }
            return true;
        }
        return false;
    }
    private static int setupLocalPalette(Palette<BlockState> vp, Reference2IntOpenHashMap<BlockState> blockCache, Mapper mapper, int[] pc) {
        int c = vp.getSize();
        if (vp instanceof LinearPalette<BlockState>) {
            for (int i = 0; i < vp.getSize(); i++) {
                var state = vp.valueFor(i);
                int blockId = -1;
                if (state != null) {
                    blockId = resolveStateToBlockId(state, blockCache, mapper);
                }
                pc[i] = blockId;
            }
        } else if (vp instanceof HashMapPalette<BlockState> pal) {
            //var map = pal.map;
            //TODO: heavily optimize this by reading the map directly

            for (int i = 0; i < vp.getSize(); i++) {
                BlockState state = null;
                int blockId = -1;
                try { state = vp.valueFor(i); } catch (Exception e) {}
                if (state != null) {
                    blockId = resolveStateToBlockId(state, blockCache, mapper);
                }
                pc[i] = blockId;
            }

        } else if (vp instanceof SingleValuePalette<BlockState>) {
            int blockId = -1;
            var state = vp.valueFor(0);
            if (state != null) {
                blockId = resolveStateToBlockId(state, blockCache, mapper);
            }
            pc[0] = blockId;
        } else {
            if (!(LITHIUM_INSTALLED && setupLithiumLocalPallet(vp, blockCache, mapper, pc))) {
                throw new IllegalStateException("Unknown palette type: " + vp);
            }
        }
        return c;
    }

    public static VoxelizedSection convert(VoxelizedSection section,
                                           Mapper stateMapper,
                                           PalettedContainer<BlockState> blockContainer,
                                           PalettedContainerRO<Holder<Biome>> biomeContainer,
                                           ILightingSupplier lightSupplier) {

        //Cheat by creating a local pallet then read the data directly


        var cache = THREAD_LOCAL.get();
        var blockCache = cache.getLocalMapping(stateMapper);

        var biomes = cache.biomeCache;
        var data = section.section;

        var vp = blockContainer.data.palette;
        var pc = cache.getPaletteCache(vp.getSize());
        GlobalPalette<BlockState> bps = null;

        int pcc = 0;
        if (blockContainer.data.palette instanceof GlobalPalette<BlockState> _bps) {
            bps = _bps;
            pcc = bps.getSize();
        } else {
            pcc = setupLocalPalette(vp, blockCache, stateMapper, pc);
            pcc = Math.max(0,pcc-1);
        }

        {
            var biomeIdCache = cache.getLocalBiomeMapping(stateMapper);
            int i = 0;
            for (int y = 0; y < 4; y++) {
                for (int z = 0; z < 4; z++) {
                    for (int x = 0; x < 4; x++) {
                        var holder = biomeContainer.get(x, y, z);
                        int biomeId = biomeIdCache.getInt(holder);
                        if (biomeId == -1) {
                            biomeId = stateMapper.getIdForBiome(holder);
                            biomeIdCache.put(holder, biomeId);
                        }
                        biomes[i++] = biomeId;
                    }
                }
            }
        }


        int nonZeroCnt = 0;
        if (blockContainer.data.storage instanceof SimpleBitStorage bStor) {
            var bDat = bStor.getRaw();
            int iterPerLong = (64 / bStor.getBits()) - 1;

            int MSK = (1 << bStor.getBits()) - 1;
            int eBits = bStor.getBits();

            long sample = 0;
            int c = 0;
            int dec = 0;
            for (int i = 0; i <= 0xFFF; i++) {
                if (dec-- == 0) {
                    sample = bDat[c++];
                    dec = iterPerLong;
                }
                int bId;
                if (bps == null) {
                    bId = pc[Math.min((int) (sample & MSK), pcc)];
                } else {
                    // GlobalPalette path: per-voxel state lookup. Apply the proxy
                    // resolver per voxel (it's a single ConcurrentHashMap lookup
                    // for the resolved cache plus an identity-pass for blocks
                    // with no resolver registered). Cannot batch-cache like the
                    // local-palette paths because there is no pc[] cache here.
                    bId = resolveStateToBlockId(bps.valueFor((int) (sample&MSK)), blockCache, stateMapper);
                }
                sample >>>= eBits;

                byte light = lightSupplier.supply(i&0xF, (i>>8)&0xF, (i>>4)&0xF);
                nonZeroCnt += (bId != 0)?1:0;
                data[i] = Mapper.composeMappingId(light, bId, biomes[Integer.compress(i,0b1100_1100_1100)]);
            }
        } else {
            if (!(blockContainer.data.storage instanceof ZeroBitStorage)) {
                throw new IllegalStateException();
            }
            int bId = pc[0];
            if (bId == 0) {//Its air
                for (int i = 0; i <= 0xFFF; i++) {
                    data[i] = Mapper.airWithLight(lightSupplier.supply(i&0xF, (i>>8)&0xF, (i>>4)&0xF));
                }
            } else {
                nonZeroCnt = 4096;
                for (int i = 0; i <= 0xFFF; i++) {
                    byte light = lightSupplier.supply(i&0xF, (i>>8)&0xF, (i>>4)&0xF);
                    data[i] = Mapper.composeMappingId(light, bId, biomes[Integer.compress(i,0b1100_1100_1100)]);
                }
            }
        }
        section.lvl0NonAirCount = nonZeroCnt;
        return section;
    }









    private static int G(int x, int y, int z) {
        return ((y<<8)|(z<<4)|x);
    }

    private static int H(int x, int y, int z) {
        return ((y<<6)|(z<<3)|x) + 16*16*16;
    }

    private static int I(int x, int y, int z) {
        return ((y<<4)|(z<<2)|x) + 8*8*8 + 16*16*16;
    }

    private static int J(int x, int y, int z) {
        return ((y<<2)|(z<<1)|x) + 4*4*4 + 8*8*8 + 16*16*16;
    }

    public static void mipSection(VoxelizedSection section, Mapper mapper) {
        var data = section.section;

        //Mip L1
        int i = 0;
        int MSK = 0b1110_1110_1110;
        int iMSK1 = (~MSK)+1;
        int q = 0;
        while (true) {
            data[16*16*16 + i++] = Mipper.mip(
                    data[q|G(0,0,0)], data[q|G(1,0,0)], data[q|G(0,0,1)], data[q|G(1,0,1)],
                    data[q|G(0,1,0)], data[q|G(1,1,0)], data[q|G(0,1,1)], data[q|G(1,1,1)],
                    mapper
            );
            if (q == MSK)
                break;
            q = (q+iMSK1)&MSK;
        }

        //Mip L2
        i = 0;
        for (int y = 0; y < 8; y+=2) {
            for (int z = 0; z < 8; z += 2) {
                for (int x = 0; x < 8; x += 2) {
                    data[16*16*16 + 8*8*8 + i++] =
                            Mipper.mip(
                                    data[H(x, y, z)],       data[H(x+1, y, z)],       data[H(x, y, z+1)],      data[H(x+1, y, z+1)],
                                    data[H(x, y+1, z)],  data[H(x+1, y+1, z)],  data[H(x, y+1, z+1)], data[H(x+1, y+1, z+1)],
                                    mapper);
                }
            }
        }

        //Mip L3
        i = 0;
        for (int y = 0; y < 4; y+=2) {
            for (int z = 0; z < 4; z += 2) {
                for (int x = 0; x < 4; x += 2) {
                    data[16*16*16 + 8*8*8 + 4*4*4 + i++] =
                            Mipper.mip(
                                    data[I(x, y, z)],       data[I(x+1, y, z)],       data[I(x, y, z+1)],      data[I(x+1, y, z+1)],
                                    data[I(x, y+1, z)],   data[I(x+1, y+1, z)],  data[I(x, y+1, z+1)], data[I(x+1, y+1, z+1)],
                                    mapper);
                }
            }
        }

        //Mip L4
        data[16*16*16 + 8*8*8 + 4*4*4 + 2*2*2] =
                Mipper.mip(
                        data[J(0, 0, 0)], data[J(1, 0, 0)], data[J(0, 0, 1)], data[J(1, 0, 1)],
                        data[J(0, 1, 0)], data[J(1, 1, 0)], data[J(0, 1, 1)], data[J(1, 1, 1)],
                        mapper);
    }
}
