package me.cortex.voxy.client.core.model.berbake;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.profiling.InactiveProfiler;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.storage.WritableLevelData;

/**
 * Minimal synthetic {@link Level} for baking a single block entity's LOD off-world.
 *
 * Returns the bake-target state at {@link BlockPos#ZERO}, a configurable fill state
 * elsewhere (used by the determinism gate to vary neighbour context), full-bright
 * light, and no neighbour block entities. Every other part of the Level surface throws
 * {@link UnsupportedOperationException} — that is the safety gate: a BlockEntityRenderer
 * that reaches beyond {@code getBlockState / getBlockEntity / light} throws, the bakery
 * catches it, and the block falls back to the particle cube. So an unsafe renderer
 * degrades to today's behaviour rather than producing a wrong LOD.
 *
 * Construction borrows registry/dimension args from the live client level (bakes only
 * run with a live level), so we don't fabricate a dimension type or registry access.
 */
public final class BakeLevel extends Level {
    private BlockState target = Blocks.AIR.defaultBlockState();
    private BlockState neighbourFill = Blocks.AIR.defaultBlockState();
    // Optional per-face overrides (indexed by Direction.get3DDataValue), null = use fill.
    // Lets the determinism gate probe one connection axis at a time; unused (all null)
    // in the default uniform-fill mode.
    private final BlockState[] faceOverride = new BlockState[6];

    public BakeLevel(ClientLevel live) {
        super((WritableLevelData) live.getLevelData(),
              live.dimension(),
              live.registryAccess(),
              live.dimensionTypeRegistration(),
              () -> InactiveProfiler.INSTANCE,
              true,    // isClientSide
              false,   // isDebug
              0L,      // biomeZoomSeed
              1000000);// maxChainedNeighborUpdates
    }

    public BakeLevel setTarget(BlockState state) {
        this.target = state;
        return this;
    }

    /** Fill returned for every non-origin position; vary it to probe neighbour-dependence. */
    public BakeLevel setNeighbourFill(BlockState state) {
        this.neighbourFill = state;
        return this;
    }

    /** Clear all per-face overrides (back to uniform fill). */
    public BakeLevel clearFaceNeighbours() {
        java.util.Arrays.fill(this.faceOverride, null);
        return this;
    }

    /** Override a single face-adjacent neighbour (rest follow the fill). */
    public BakeLevel setFaceNeighbour(net.minecraft.core.Direction dir, BlockState state) {
        this.faceOverride[dir.get3DDataValue()] = state;
        return this;
    }

    // ---- controlled surface (the only state a determined BER may read) ----

    @Override
    public BlockState getBlockState(BlockPos pos) {
        int x = pos.getX(), y = pos.getY(), z = pos.getZ();
        if (x == 0 && y == 0 && z == 0) {
            return this.target;
        }
        if (Math.abs(x) + Math.abs(y) + Math.abs(z) == 1) {//face-adjacent cell
            for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) {
                var n = d.getNormal();
                if (n.getX() == x && n.getY() == y && n.getZ() == z) {
                    BlockState o = this.faceOverride[d.get3DDataValue()];
                    if (o != null) return o;
                    break;
                }
            }
        }
        return this.neighbourFill;
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return getBlockState(pos).getFluidState();
    }

    @Override
    public BlockEntity getBlockEntity(BlockPos pos) {
        return null;
    }

    @Override
    public int getBrightness(LightLayer layer, BlockPos pos) {
        return 15;
    }

    @Override
    public int getRawBrightness(BlockPos pos, int amount) {
        return 15;
    }

    @Override
    public float getShade(net.minecraft.core.Direction direction, boolean shade) {
        return 1.0F; // flat albedo; voxy relights the LOD itself
    }

    // ---- unmodelled surface: throw (the gate) ----

    @Override public void sendBlockUpdated(BlockPos p1, BlockState p2, BlockState p3, int p4) { throw gate(); }
    @Override public void playSeededSound(net.minecraft.world.entity.player.Player p1, double p2, double p3, double p4, net.minecraft.core.Holder<net.minecraft.sounds.SoundEvent> p5, net.minecraft.sounds.SoundSource p6, float p7, float p8, long p9) { throw gate(); }
    @Override public void playSeededSound(net.minecraft.world.entity.player.Player p1, net.minecraft.world.entity.Entity p2, net.minecraft.core.Holder<net.minecraft.sounds.SoundEvent> p3, net.minecraft.sounds.SoundSource p4, float p5, float p6, long p7) { throw gate(); }
    @Override public String gatherChunkSourceStats() { throw gate(); }
    @Override public net.minecraft.world.entity.Entity getEntity(int id) { throw gate(); }
    @Override public net.minecraft.world.level.saveddata.maps.MapItemSavedData getMapData(String id) { throw gate(); }
    @Override public void setMapData(String id, net.minecraft.world.level.saveddata.maps.MapItemSavedData data) { throw gate(); }
    @Override public int getFreeMapId() { throw gate(); }
    @Override public void destroyBlockProgress(int p1, BlockPos p2, int p3) { throw gate(); }
    @Override public net.minecraft.world.scores.Scoreboard getScoreboard() { throw gate(); }
    @Override public net.minecraft.world.item.crafting.RecipeManager getRecipeManager() { throw gate(); }
    @Override protected net.minecraft.world.level.entity.LevelEntityGetter<net.minecraft.world.entity.Entity> getEntities() { throw gate(); }
    @Override public net.minecraft.world.level.chunk.ChunkSource getChunkSource() { throw gate(); }
    @Override public java.util.List<? extends net.minecraft.world.entity.player.Player> players() { throw gate(); }
    @Override public net.minecraft.world.ticks.LevelTickAccess<net.minecraft.world.level.block.Block> getBlockTicks() { throw gate(); }
    @Override public net.minecraft.world.ticks.LevelTickAccess<net.minecraft.world.level.material.Fluid> getFluidTicks() { throw gate(); }
    @Override public void gameEvent(net.minecraft.world.level.gameevent.GameEvent p1, net.minecraft.world.phys.Vec3 p2, net.minecraft.world.level.gameevent.GameEvent.Context p3) { throw gate(); }
    @Override public void levelEvent(net.minecraft.world.entity.player.Player p1, int p2, BlockPos p3, int p4) { throw gate(); }
    @Override public net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome> getUncachedNoiseBiome(int p1, int p2, int p3) { throw gate(); }
    @Override public net.minecraft.world.flag.FeatureFlagSet enabledFeatures() { throw gate(); }

    private static UnsupportedOperationException gate() {
        return new UnsupportedOperationException("BakeLevel: BlockEntityRenderer reached unmodelled Level surface");
    }
}
