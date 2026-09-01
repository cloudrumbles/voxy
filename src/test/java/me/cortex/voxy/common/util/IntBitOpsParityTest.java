package me.cortex.voxy.common.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Random;
import org.junit.jupiter.api.Test;

final class IntBitOpsParityTest {
    private static int referenceCompress(int value, int mask) {
        int result = 0;
        int target = 1;
        for (int bit = 0; bit < Integer.SIZE; bit++) {
            int source = 1 << bit;
            if ((mask & source) != 0) {
                if ((value & source) != 0) {
                    result |= target;
                }
                target <<= 1;
            }
        }
        return result;
    }

    private static int referenceExpand(int value, int mask) {
        int result = 0;
        int source = 1;
        for (int bit = 0; bit < Integer.SIZE; bit++) {
            int target = 1 << bit;
            if ((mask & target) != 0) {
                if ((value & source) != 0) {
                    result |= target;
                }
                source <<= 1;
            }
        }
        return result;
    }

    private static long referenceCompress(long value, long mask) {
        long result = 0;
        long target = 1;
        for (int bit = 0; bit < Long.SIZE; bit++) {
            long source = 1L << bit;
            if ((mask & source) != 0) {
                if ((value & source) != 0) {
                    result |= target;
                }
                target <<= 1;
            }
        }
        return result;
    }

    private static long referenceExpand(long value, long mask) {
        long result = 0;
        long source = 1;
        for (int bit = 0; bit < Long.SIZE; bit++) {
            long target = 1L << bit;
            if ((mask & target) != 0) {
                if ((value & source) != 0) {
                    result |= target;
                }
                source <<= 1;
            }
        }
        return result;
    }

    @Test
    void exhaustiveLowByteParity() {
        for (int value = 0; value <= 0xff; value++) {
            for (int mask = 0; mask <= 0xff; mask++) {
                assertEquals(referenceCompress(value, mask), IntBitOps.compress(value, mask));
                assertEquals(referenceExpand(value, mask), IntBitOps.expand(value, mask));
            }
        }
    }

    @Test
    void randomFullWidthParityAndRoundTrip() {
        Random random = new Random(0x564f58594cL);
        for (int sample = 0; sample < 100_000; sample++) {
            int value = random.nextInt();
            int mask = random.nextInt();

            int compressed = IntBitOps.compress(value, mask);
            int expanded = IntBitOps.expand(compressed, mask);

            assertEquals(referenceCompress(value, mask), compressed);
            assertEquals(referenceExpand(compressed, mask), expanded);
            assertEquals(value & mask, expanded);
        }
    }

    @Test
    void randomLongParityAndRoundTrip() {
        Random random = new Random(0x564f58594c4f4e47L);
        for (int sample = 0; sample < 100_000; sample++) {
            long value = random.nextLong();
            long mask = random.nextLong();

            long compressed = IntBitOps.compress(value, mask);
            long expanded = IntBitOps.expand(compressed, mask);

            assertEquals(referenceCompress(value, mask), compressed);
            assertEquals(referenceExpand(compressed, mask), expanded);
            assertEquals(value & mask, expanded);
        }
    }

    @Test
    void edgeCasesIncludeSignBitAndAllBits() {
        int[] intValues = {0, -1, Integer.MIN_VALUE, Integer.MAX_VALUE, 0x55555555, 0xaaaaaaaa};
        int[] intMasks = {0, -1, Integer.MIN_VALUE, Integer.MAX_VALUE, 0x55555555, 0xaaaaaaaa};
        for (int value : intValues) {
            for (int mask : intMasks) {
                assertEquals(referenceCompress(value, mask), IntBitOps.compress(value, mask));
                assertEquals(referenceExpand(value, mask), IntBitOps.expand(value, mask));
            }
        }

        long[] longValues = {0, -1, Long.MIN_VALUE, Long.MAX_VALUE,
                0x5555555555555555L, 0xaaaaaaaaaaaaaaaaL};
        long[] longMasks = {0, -1, Long.MIN_VALUE, Long.MAX_VALUE,
                0x5555555555555555L, 0xaaaaaaaaaaaaaaaaL};
        for (long value : longValues) {
            for (long mask : longMasks) {
                assertEquals(referenceCompress(value, mask), IntBitOps.compress(value, mask));
                assertEquals(referenceExpand(value, mask), IntBitOps.expand(value, mask));
            }
        }
    }
}
