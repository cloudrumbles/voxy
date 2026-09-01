package me.cortex.voxy.common.voxelization;


import java.util.Arrays;

//16x16x16 block section
public class VoxelizedSection {
    public int x;
    public int y;
    public int z;
    public int lvl0NonAirCount;
    public final long[] section;
    public VoxelizedSection(long[] section) {
        this.section = section;
    }

    public static int getBaseIndexForLevel(int lvl) {
        int offset = lvl==1?(1<<12):0;
        offset |= lvl==2?(1<<12)|(1<<9):0;
        offset |= lvl==3?(1<<12)|(1<<9)|(1<<6):0;
        offset |= lvl==4?(1<<12)|(1<<9)|(1<<6)|(1<<3):0;
        return offset;
    }

    public VoxelizedSection setPosition(int x, int y, int z) {
        this.x = x;
        this.y = y;
        this.z = z;
        return this;
    }

    private static int getIdx(int x, int y, int z, int shiftBy, int size) {
        int M = (1<<size)-1;
        x = (x>>shiftBy)&M;
        y = (y>>shiftBy)&M;
        z = (z>>shiftBy)&M;
        return (y<<(size<<1))|(z<<size)|(x);
    }

    public long get(int lvl, int x, int y, int z) {
        int offset = lvl==1?(1<<12):0;
        offset |= lvl==2?(1<<12)|(1<<9):0;
        offset |= lvl==3?(1<<12)|(1<<9)|(1<<6):0;
        offset |= lvl==4?(1<<12)|(1<<9)|(1<<6)|(1<<3):0;
        return this.section[getIdx(x, y, z, 0, 4-lvl) + offset];
    }

    public static VoxelizedSection createEmpty() {
        return new VoxelizedSection(new long[16*16*16 + 8*8*8 + 4*4*4 + 2*2*2 + 1]);
    }

    // Clears the ENTIRE mip pyramid (LOD0 + LOD1..4). Only called from the
    // all-air shortcut paths in VoxelIngestService and VoxyDistantGenSaveService,
    // where the zeroed section is shipped directly to WorldUpdater.insertUpdate
    // without convert/mipSection running. Higher levels MUST be zero in that
    // path — nothing else writes them.
    //
    // The path with actual data (WorldConversionFactory.convert +
    // mipSection) does NOT call zero(): convert overwrites LOD0, then
    // mipSection rewrites LOD1..4 from new LOD0. Skipping zero() there is
    // intentional and safe.
    //
    // A previous audit suggested zeroing only LOD0 here for ~5 KiB of saved
    // work per ingest; that would silently leak stale LOD1..4 from the
    // thread-local cache's previous use into the all-air consumer's view.
    // Don't change this without rewriting the consumer paths too.
    public VoxelizedSection zero() {
        this.lvl0NonAirCount = 0;
        Arrays.fill(this.section, 0);
        return this;
    }
}
