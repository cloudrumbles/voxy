package me.cortex.voxy.common.util;

/** Java 17 equivalents for the JDK bit-compress and bit-expand operations. */
public final class IntBitOps {
    private IntBitOps() {}

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
}
