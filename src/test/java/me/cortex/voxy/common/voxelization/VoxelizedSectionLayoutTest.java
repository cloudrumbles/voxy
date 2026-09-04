package me.cortex.voxy.common.voxelization;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

final class VoxelizedSectionLayoutTest {
    @Test
    void levelOffsetsAndCapacityMatchUpstreamWireLayout() {
        assertEquals(0, VoxelizedSection.getBaseIndexForLevel(0));
        assertEquals(4_096, VoxelizedSection.getBaseIndexForLevel(1));
        assertEquals(4_608, VoxelizedSection.getBaseIndexForLevel(2));
        assertEquals(4_672, VoxelizedSection.getBaseIndexForLevel(3));
        assertEquals(4_680, VoxelizedSection.getBaseIndexForLevel(4));
        assertEquals(4_681, VoxelizedSection.createEmpty().section.length);
    }

    @Test
    void everyMipLevelUsesTheExpectedXzyIndexOrder() {
        VoxelizedSection section = VoxelizedSection.createEmpty();
        for (int i = 0; i < section.section.length; i++) {
            section.section[i] = i;
        }

        for (int level = 0; level <= 4; level++) {
            int side = 16 >> level;
            int offset = VoxelizedSection.getBaseIndexForLevel(level);
            for (int y = 0; y < side; y++) {
                for (int z = 0; z < side; z++) {
                    for (int x = 0; x < side; x++) {
                        int expected = offset + (y * side * side) + (z * side) + x;
                        assertEquals(expected, section.get(level, x, y, z));
                    }
                }
            }
        }
    }

    @Test
    void zeroClearsDataAndDerivedCount() {
        VoxelizedSection section = VoxelizedSection.createEmpty();
        Arrays.fill(section.section, 42L);
        section.lvl0NonAirCount = 99;
        section.zero();

        assertEquals(0, section.lvl0NonAirCount);
        for (long value : section.section) {
            assertEquals(0L, value);
        }
    }
}
