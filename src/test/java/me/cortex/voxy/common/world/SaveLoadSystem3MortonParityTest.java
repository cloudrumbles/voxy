package me.cortex.voxy.common.world;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Random;
import org.junit.jupiter.api.Test;

final class SaveLoadSystem3MortonParityTest {
    @Test
    void everySectionIndexRoundTripsThroughMortonOrder() {
        boolean[] seen = new boolean[WorldSection.SECTION_VOLUME];
        for (int linear = 0; linear < WorldSection.SECTION_VOLUME; linear++) {
            int morton = SaveLoadSystem3.lin2z(linear);
            assertEquals(linear, SaveLoadSystem3.z2lin(morton));
            seen[morton] = true;
        }
        for (boolean entry : seen) {
            assertEquals(true, entry);
        }
    }

    @Test
    void coordinateBitsMatchThePersistentStorageAxisOrder() {
        for (int y = 0; y < 32; y++) {
            for (int z = 0; z < 32; z++) {
                for (int x = 0; x < 32; x++) {
                    int linear = WorldSection.getIndex(x, y, z);
                    int expectedMorton = interleaveFiveBits(x, y, z);
                    assertEquals(expectedMorton, SaveLoadSystem3.lin2z(linear));
                }
            }
        }
    }

    @Test
    void ignoresBitsOutsideTheFiveBitCoordinates() {
        Random random = new Random(0x534156454c4f4144L);
        for (int sample = 0; sample < 100_000; sample++) {
            int linear = random.nextInt();
            int canonical = WorldSection.getIndex(linear, linear >>> 10, linear >>> 5);
            assertEquals(SaveLoadSystem3.lin2z(canonical), SaveLoadSystem3.lin2z(linear));
        }
    }

    private static int interleaveFiveBits(int x, int y, int z) {
        int result = 0;
        for (int bit = 0; bit < 5; bit++) {
            result |= ((x >>> bit) & 1) << (bit * 3);
            result |= ((y >>> bit) & 1) << (bit * 3 + 1);
            result |= ((z >>> bit) & 1) << (bit * 3 + 2);
        }
        return result;
    }
}
