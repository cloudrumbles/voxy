package me.cortex.voxy.common.world;

import it.unimi.dsi.fastutil.longs.Long2ShortOpenHashMap;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.util.MemoryBuffer;
import me.cortex.voxy.common.util.ThreadLocalMemoryBuffer;
import me.cortex.voxy.common.world.other.Mapper;
import org.lwjgl.system.MemoryUtil;

public class SaveLoadSystem3 {
    // On-disk layout: [key:8][metadata:8][32768 x u16 LUT index][lutLen x i64].
    // metadata bits: 0-15 lutLen, 16-23 nonEmptyChildren, 24-55 CRC32C of the
    // payload (index region + LUT), 56-63 storage version. Version 0 (legacy)
    // sections have zeros in bits 24-63 and skip hash verification; version 1
    // adds the CRC. CRC32C is hardware-accelerated; ~microseconds per section.
    public static final int STORAGE_VERSION = 1;

    // Upper bound on the serialised size of one WorldSection. Matches the
    // worst-case shape: SECTION_VOLUME shorts (the per-voxel LUT indices)
    // + SECTION_VOLUME longs (a degenerate LUT where every voxel is unique)
    // + an 8-byte header. Compressors and storage backends size their
    // scratch buffers against this so the load path never overruns.
    public static final int BIGGEST_SERIALIZED_SECTION_SIZE = 32 * 32 * 32 * 8 * 2 + 8;

    private record SerializationCache(Long2ShortOpenHashMap lutMapCache, MemoryBuffer memoryBuffer) {
        public SerializationCache() {
            this(new Long2ShortOpenHashMap(1024), ThreadLocalMemoryBuffer.create(WorldSection.SECTION_VOLUME*2+WorldSection.SECTION_VOLUME*8+1024));
            this.lutMapCache.defaultReturnValue((short) -1);
        }
    }
    // PDEP equivalent - distributes bits of src into positions of mask
    private static int expand(int src, int mask) {
        int result = 0;
        int srcBit = 0;
        while (mask != 0) {
            int lowestBit = mask & -mask;
            if ((src & (1 << srcBit)) != 0) {
                result |= lowestBit;
            }
            mask &= mask - 1;
            srcBit++;
        }
        return result;
    }

    // PEXT equivalent - collects bits from positions of mask
    private static int compress(int value, int mask) {
        int result = 0;
        int destBit = 0;
        while (mask != 0) {
            int lowestBit = mask & -mask;
            if ((value & lowestBit) != 0) {
                result |= (1 << destBit);
            }
            mask &= mask - 1;
            destBit++;
        }
        return result;
    }

    public static int lin2z(int i) {//y,z,x
        int x = i&0x1F;
        int y = (i>>10)&0x1F;
        int z = (i>>5)&0x1F;
        return expand(x,0b1001001001001)|expand(y,0b10010010010010)|expand(z,0b100100100100100);

        //zyxzyxzyxzyxzyx
    }

    public static int z2lin(int i) {
        int x = compress(i, 0b1001001001001);
        int y = compress(i, 0b10010010010010);
        int z = compress(i, 0b100100100100100);
        return x|(y<<10)|(z<<5);
    }

    private static final ThreadLocal<SerializationCache> CACHE = ThreadLocal.withInitial(SerializationCache::new);

    //TODO: Cache like long2short and the short and other data to stop allocs
    public static MemoryBuffer serialize(WorldSection section) {
        var cache = CACHE.get();
        var data = section.data;

        Long2ShortOpenHashMap LUT = cache.lutMapCache; LUT.clear();

        MemoryBuffer buffer = cache.memoryBuffer().createUntrackedUnfreeableReference();
        long ptr = buffer.address;

        MemoryUtil.memPutLong(ptr, section.key); ptr += 8;
        long metadataPtr = ptr; ptr += 8;

        long blockPtr = ptr; ptr += WorldSection.SECTION_VOLUME*2;
        for (long block : data) {
            short mapping = LUT.putIfAbsent(block, (short) LUT.size());
            if (mapping == -1) {
                mapping = (short) (LUT.size()-1);
                MemoryUtil.memPutLong(ptr, block); ptr+=8;
            }
            MemoryUtil.memPutShort(blockPtr, mapping); blockPtr+=2;
        }

        long metadata = 0;
        metadata |= Integer.toUnsignedLong(LUT.size());//Bottom 2 bytes
        metadata |= Byte.toUnsignedLong(section.getNonEmptyChildren())<<16;//Next byte

        // CRC32C over the payload (index region + LUT). Without it, a
        // truncated or bit-flipped value read garbage mapping ids silently;
        // the only prior integrity check was the key echo.
        var crc = new java.util.zip.CRC32C();
        long payloadStart = metadataPtr + 8;
        crc.update(MemoryUtil.memByteBuffer(payloadStart, (int) (ptr - payloadStart)));
        metadata |= (crc.getValue() & 0xFFFFFFFFL) << 24;
        metadata |= ((long) STORAGE_VERSION) << 56;

        MemoryUtil.memPutLong(metadataPtr, metadata);

        // Shrink the thread-local LUT backing arrays when the section was small.
        // Long2ShortOpenHashMap keeps its capacity at high-water-mark after
        // clear(), so a single outlier heterogeneous section permanently
        // inflates this worker's footprint. trim() is a no-op when already at
        // minimum capacity, so common-case cost is just the size check.
        if (LUT.size() < 256) {
            LUT.trim(1024);
        }

        return buffer.subSize(ptr-buffer.address);//Does not get freed
    }

    public static boolean deserialize(WorldSection section, MemoryBuffer data) {
        long ptr = data.address;
        long key = MemoryUtil.memGetLong(ptr); ptr += 8;

        if (section.key != key) {
            //throw new IllegalStateException("Decompressed section not the same as requested. got: " + key + " expected: " + section.key);
            Logger.error("Decompressed section not the same as requested. got: " + key + " expected: " + section.key);
            return false;
        }

        final long metadata = MemoryUtil.memGetLong(ptr); ptr += 8;
        section.nonEmptyChildren = (byte) ((metadata>>>16)&0xFF);
        final long lutBasePtr = ptr + WorldSection.SECTION_VOLUME * 2;

        // Integrity / version gate (see STORAGE_VERSION comment for layout).
        {
            int version = (int) (metadata >>> 56) & 0xFF;
            int lutLen = (int) (metadata & 0xFFFF);
            long payloadEnd = lutBasePtr + lutLen * 8L;
            if (payloadEnd - data.address > data.size) {
                Logger.error("Section " + section.key + " payload exceeds buffer (lutLen " + lutLen + "); corrupt");
                return false;
            }
            if (version == STORAGE_VERSION) {
                var crc = new java.util.zip.CRC32C();
                crc.update(MemoryUtil.memByteBuffer(ptr, (int) (payloadEnd - ptr)));
                long stored = (metadata >>> 24) & 0xFFFFFFFFL;
                if ((crc.getValue() & 0xFFFFFFFFL) != stored) {
                    Logger.error("Section " + section.key + " failed CRC32C check; corrupt");
                    return false;
                }
            } else if (version != 0) { // 0 = legacy pre-hash sections, accepted as-is
                Logger.error("Section " + section.key + " has unknown storage version " + version);
                return false;
            }
        }
        if (section.lvl == 0) {
            int nonEmptyBlockCount = 0;
            final var blockData = section.data;
            for (int i = 0; i < WorldSection.SECTION_VOLUME; i++) {
                final short lutId = MemoryUtil.memGetShort(ptr); ptr += 2;
                final long blockId = MemoryUtil.memGetLong(lutBasePtr + Short.toUnsignedLong(lutId) * 8L);
                nonEmptyBlockCount += Mapper.isAir(blockId) ? 0 : 1;
                blockData[i] = blockId;
            }
            section.nonEmptyBlockCount = nonEmptyBlockCount;
        } else {
            final var blockData = section.data;
            for (int i = 0; i < WorldSection.SECTION_VOLUME; i++) {
                blockData[i] = MemoryUtil.memGetLong(lutBasePtr + Short.toUnsignedLong(MemoryUtil.memGetShort(ptr)) * 8L);ptr += 2;
            }
        }
        ptr = lutBasePtr + (metadata & 0xFFFF) * 8L;
        // The loop above wrote real on-disk data into section.data; clear the
        // air-cache routing flag in case this section was constructed by
        // pulling an already-air-filled array from AIR_REUSE_CACHE (the
        // invariant no longer holds).
        section.dataIsKnownAir = false;
        return true;
    }
}
