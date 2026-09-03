package me.cortex.voxy.common.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.BitSet;
import java.util.Random;
import org.junit.jupiter.api.Test;

final class HierarchicalBitSetRegressionTest {
    @Test
    void allocatesLowestFreeSlotAndMaintainsBookkeeping() {
        HierarchicalBitSet allocator = new HierarchicalBitSet(8_192);
        BitSet model = new BitSet(8_192);
        Random random = new Random(0x564f5859L);

        for (int operation = 0; operation < 40_000; operation++) {
            boolean allocate = model.isEmpty()
                    || (model.cardinality() < allocator.getLimit() && random.nextBoolean());
            if (allocate) {
                int expected = model.nextClearBit(0);
                int actual = allocator.allocateNext();
                assertEquals(expected, actual);
                model.set(actual);
            } else {
                int ordinal = random.nextInt(model.cardinality());
                int index = model.nextSetBit(0);
                while (ordinal-- > 0) {
                    index = model.nextSetBit(index + 1);
                }
                assertTrue(allocator.free(index));
                assertFalse(allocator.free(index));
                model.clear(index);
            }

            assertEquals(model.cardinality(), allocator.getCount());
            assertEquals(model.length() - 1, allocator.getMaxIndex());
        }
    }

    @Test
    void allocatesConsecutiveRangesAcrossWordBoundaries() {
        HierarchicalBitSet allocator = new HierarchicalBitSet(4_096);
        for (int i = 0; i < 60; i++) {
            assertEquals(i, allocator.allocateNext());
        }

        int start = allocator.allocateNextConsecutiveCounted(16);
        assertEquals(60, start);
        for (int i = start; i < start + 16; i++) {
            assertTrue(allocator.isSet(i));
        }

        for (int i = 62; i < 70; i++) {
            assertTrue(allocator.free(i));
        }

        int replacement = allocator.allocateNextConsecutiveCounted(8);
        assertEquals(62, replacement);
    }

    @Test
    void honoursConfiguredCapacity() {
        HierarchicalBitSet allocator = new HierarchicalBitSet(256);
        for (int i = 0; i < 256; i++) {
            assertEquals(i, allocator.allocateNext());
        }
        assertEquals(-1, allocator.allocateNext());
        assertEquals(256, allocator.getCount());
    }
}
