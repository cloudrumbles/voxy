package me.cortex.voxy.common.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.Test;

final class WorldCoordinateParityTest {
    @Test
    void sectionIndexMatchesUpstreamLayout() {
        boolean[] seen = new boolean[WorldSection.SECTION_VOLUME];
        for (int y = 0; y < 32; y++) {
            for (int z = 0; z < 32; z++) {
                for (int x = 0; x < 32; x++) {
                    int expected = (y << 10) | (z << 5) | x;
                    int actual = WorldSection.getIndex(x, y, z);
                    assertEquals(expected, actual);
                    seen[actual] = true;
                }
            }
        }
        for (boolean entry : seen) {
            assertTrue(entry);
        }
    }

    @Test
    void childIndexMatchesUpstreamAxisOrder() {
        for (int y = 0; y < 2; y++) {
            for (int z = 0; z < 2; z++) {
                for (int x = 0; x < 2; x++) {
                    assertEquals(x | (z << 1) | (y << 2), WorldSection.getChildIndex(x, y, z));
                }
            }
        }
    }

    @Test
    void packedSectionCoordinatesRoundTripAtFormatBoundaries() {
        int[] levels = {0, 1, 4, 15};
        int[] horizontal = {-8_388_608, -1, 0, 1, 8_388_607};
        int[] vertical = {-128, -1, 0, 1, 127};

        for (int level : levels) {
            for (int x : horizontal) {
                for (int y : vertical) {
                    for (int z : horizontal) {
                        long id = WorldEngine.getWorldSectionId(level, x, y, z);
                        assertEquals(level, WorldEngine.getLevel(id));
                        assertEquals(x, WorldEngine.getX(id));
                        assertEquals(y, WorldEngine.getY(id));
                        assertEquals(z, WorldEngine.getZ(id));
                    }
                }
            }
        }
    }

    @Test
    void randomPackedCoordinatesRoundTrip() {
        Random random = new Random(0x1192f043L);
        for (int i = 0; i < 100_000; i++) {
            int level = random.nextInt(16);
            int x = random.nextInt(1 << 24) - (1 << 23);
            int y = random.nextInt(256) - 128;
            int z = random.nextInt(1 << 24) - (1 << 23);
            long id = WorldEngine.getWorldSectionId(level, x, y, z);
            assertEquals(level, WorldEngine.getLevel(id));
            assertEquals(x, WorldEngine.getX(id));
            assertEquals(y, WorldEngine.getY(id));
            assertEquals(z, WorldEngine.getZ(id));
        }
    }
}
