package me.cortex.voxy.common.config.compressors;

import com.github.luben.zstd.Zstd;
import me.cortex.voxy.common.config.ConfigBuildCtx;
import me.cortex.voxy.common.config.section.SectionSerializationStorage;
import me.cortex.voxy.common.util.MemoryBuffer;
import me.cortex.voxy.common.util.ResizingThreadLocalMemoryBuffer;

/**
 * Zstandard compression backed by zstd-jni.
 *
 * <p>Minecraft 1.19.2 ships LWJGL core 3.3.1, but not the optional
 * lwjgl-zstd native module. Keeping the compressor on LWJGL therefore made an
 * otherwise self-contained Forge mod dependent on a native that ordinary
 * clients do not have. zstd-jni carries its own platform natives while writing
 * the same standard Zstandard frames, so existing Voxy data remains compatible.</p>
 */
public class ZSTDCompressor implements StorageCompressor {
    private static final ResizingThreadLocalMemoryBuffer SCRATCH =
            new ResizingThreadLocalMemoryBuffer(SectionSerializationStorage.BIGGEST_SERIALIZED_SECTION_SIZE + 1024);

    private final int level;

    public ZSTDCompressor(int level) {
        this.level = level;
    }

    @Override
    public MemoryBuffer compress(MemoryBuffer saveData) {
        long bound = Zstd.compressBound(saveData.size);
        var compressedData = SCRATCH.get(bound).createUntrackedUnfreeableReference();
        long compressedSize = requireSuccess(
                Zstd.compressUnsafe(
                        compressedData.address,
                        compressedData.size,
                        saveData.address,
                        saveData.size,
                        this.level),
                "compression");
        return compressedData.subSize(compressedSize);
    }

    @Override
    public MemoryBuffer decompress(MemoryBuffer saveData) {
        var decompressed = SCRATCH.get().createUntrackedUnfreeableReference();
        long decompressedSize = requireSuccess(
                Zstd.decompressUnsafe(
                        decompressed.address,
                        decompressed.size,
                        saveData.address,
                        saveData.size),
                "decompression");
        return decompressed.subSize(decompressedSize);
    }

    private static long requireSuccess(long result, String operation) {
        if (Zstd.isError(result)) {
            throw new IllegalStateException("Zstandard " + operation + " failed: "
                    + Zstd.getErrorName(result));
        }
        if (result <= 0L) {
            throw new IllegalStateException("Zstandard " + operation
                    + " produced an invalid byte count: " + result);
        }
        return result;
    }

    @Override
    public void close() {
        // zstd-jni's one-shot API owns no Java-side context for us to release.
    }

    public static class Config extends CompressorConfig {
        public int compressionLevel;

        @Override
        public StorageCompressor build(ConfigBuildCtx ctx) {
            return new ZSTDCompressor(this.compressionLevel);
        }

        public static String getConfigTypeName() {
            return "ZSTD";
        }
    }
}
