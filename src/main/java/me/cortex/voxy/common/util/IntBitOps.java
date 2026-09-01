package me.cortex.voxy.common.util;

/**
 * Java 17 equivalents of the bit compress/expand operations added to the JDK
 * after Minecraft 1.19.2's supported runtime.
 *
 * <p>The methods preserve the exact PEXT/PDEP-style semantics of
 * {@code Integer.compress}, {@code Integer.expand}, {@code Long.compress}, and
 * {@code Long.expand}: only positions selected by {@code mask} participate and
 * all other bits are discarded or left clear.</p>
 */
public final class IntBitOps {
    private IntBitOps() {
    }

    public static int compress(int value, int mask) {
        int result = 0;
        int outputBit = 1;
        for (int remaining = mask; remaining != 0; remaining &= remaining - 1) {
            int selectedBit = remaining & -remaining;
            if ((value & selectedBit) != 0) {
                result |= outputBit;
            }
            outputBit <<= 1;
        }
        return result;
    }

    public static int expand(int value, int mask) {
        int result = 0;
        int inputBit = 1;
        for (int remaining = mask; remaining != 0; remaining &= remaining - 1) {
            int selectedBit = remaining & -remaining;
            if ((value & inputBit) != 0) {
                result |= selectedBit;
            }
            inputBit <<= 1;
        }
        return result;
    }

    public static long compress(long value, long mask) {
        long result = 0;
        long outputBit = 1;
        for (long remaining = mask; remaining != 0; remaining &= remaining - 1) {
            long selectedBit = remaining & -remaining;
            if ((value & selectedBit) != 0) {
                result |= outputBit;
            }
            outputBit <<= 1;
        }
        return result;
    }

    public static long expand(long value, long mask) {
        long result = 0;
        long inputBit = 1;
        for (long remaining = mask; remaining != 0; remaining &= remaining - 1) {
            long selectedBit = remaining & -remaining;
            if ((value & inputBit) != 0) {
                result |= selectedBit;
            }
            inputBit <<= 1;
        }
        return result;
    }
}
