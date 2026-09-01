package me.cortex.voxy.common.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class WorldSectionLifecycleTest {
    @Test
    void untrackedSectionFollowsTheReferenceCountContract() {
        WorldSection section = WorldSection._createRawUntrackedUnsafeSection(2, -17, 3, 41);

        assertEquals(WorldEngine.getWorldSectionId(2, -17, 3, 41), section.key);
        assertEquals(0, section.getRefCount());
        assertFalse(section.isFreed());

        assertEquals(1, section.acquire());
        assertEquals(1, section.getRefCount());
        assertEquals(0, section.release(false));
        assertEquals(0, section.getRefCount());
        assertFalse(section.isFreed());

        assertEquals(1, section.acquire());
        assertEquals(0, section.release());
        assertTrue(section.isFreed());
        assertThrows(IllegalStateException.class, section::acquire);
    }

    @Test
    void voxelIndexingAndCopiesPreserveTheUpstreamXzyLayout() {
        WorldSection section = WorldSection._createRawUntrackedUnsafeSection(0, 0, 0, 0);
        try {
            for (int y = 0; y < 32; y++) {
                for (int z = 0; z < 32; z++) {
                    for (int x = 0; x < 32; x++) {
                        long value = Integer.toUnsignedLong((y << 10) | (z << 5) | x) + 1;
                        assertEquals(0L, section.set(x, y, z, value));
                    }
                }
            }

            long[] copy = section.copyData();
            assertEquals(WorldSection.SECTION_VOLUME, copy.length);
            for (int i = 0; i < copy.length; i++) {
                assertEquals(i + 1L, copy[i]);
            }
        } finally {
            section.acquire();
            section.release();
        }
    }

    @Test
    void childIndexUsesXThenZThenYBits() {
        for (int y = 0; y < 2; y++) {
            for (int z = 0; z < 2; z++) {
                for (int x = 0; x < 2; x++) {
                    assertEquals(x | (z << 1) | (y << 2), WorldSection.getChildIndex(x, y, z));
                }
            }
        }
    }
}
