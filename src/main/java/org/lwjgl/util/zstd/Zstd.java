package org.lwjgl.util.zstd;

import java.nio.ByteBuffer;

/**
 * Compatibility bridge for the small subset of LWJGL's Zstandard API used by
 * the legacy Distant Horizons importer.
 *
 * <p>The Forge 1.19.2 client does not ship lwjgl-zstd. Voxy's compressor and
 * importer instead share the self-contained zstd-jni runtime. The context
 * handle accepted by the old methods is intentionally opaque because the
 * bridge uses zstd-jni's one-shot operations.</p>
 */
public final class Zstd {
    private static final long VALID_CONTEXT = 1L;

    private Zstd() {
    }

    public static long ZSTD_createDCtx() {
        return VALID_CONTEXT;
    }

    public static long ZSTD_freeDCtx(long context) {
        requireContext(context);
        return 0L;
    }

    public static long ZSTD_getFrameContentSize(ByteBuffer source) {
        requireDirect(source, "source");
        return com.github.luben.zstd.Zstd.getFrameContentSize(source);
    }

    public static long ZSTD_decompressDCtx(long context, ByteBuffer source, ByteBuffer destination) {
        requireContext(context);
        requireDirect(source, "source");
        requireDirect(destination, "destination");
        return com.github.luben.zstd.Zstd.decompressDirectByteBuffer(
                destination,
                destination.position(),
                destination.remaining(),
                source,
                source.position(),
                source.remaining());
    }

    public static boolean ZSTD_isError(long result) {
        return com.github.luben.zstd.Zstd.isError(result);
    }

    public static String ZSTD_getErrorName(long result) {
        return com.github.luben.zstd.Zstd.getErrorName(result);
    }

    private static void requireContext(long context) {
        if (context != VALID_CONTEXT) {
            throw new IllegalArgumentException("Invalid compatibility Zstandard context: " + context);
        }
    }

    private static void requireDirect(ByteBuffer buffer, String name) {
        if (buffer == null || !buffer.isDirect()) {
            throw new IllegalArgumentException(name + " must be a direct ByteBuffer");
        }
    }
}
