package me.cortex.voxy.compat.dynamictrees;

import com.ferreusveritas.dynamictrees.block.branch.BranchBlock;
import com.ferreusveritas.dynamictrees.block.branch.SurfaceRootBlock;
import com.ferreusveritas.dynamictrees.block.branch.TrunkShellBlock;
import com.ferreusveritas.dynamictrees.tree.family.Family;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.compat.IStateProxyResolver;
import me.cortex.voxy.common.compat.VoxyStateProxyRegistry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.LoadingModList;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Substitute DT branch / trunk / root BlockStates with a vanilla wood (or
 * log-fallback) BlockState at voxy ingest time. Voxy's per-state bakery has
 * no neighbour context, so the connection-aware DT geometry it would emit
 * is wrong — gaps between stacked trunk voxels, no extension into branch
 * neighbours. Substituting at ingest lets the bakery store an
 * orientation-irrelevant wood stamp per tree species; in LOD a DT forest
 * reads as a forest of vanilla-shaped trees at the same scale.
 *
 * Mapping chain per branch state:
 *   1. family.getPrimitiveLog() -> log Block            (set by every DT addon)
 *   2. log -> sibling wood Block by name convention      (best path, no AXIS artifacts)
 *   3. wood missing -> use the log itself                (visible-but-with-end-grain-from-above)
 *   4. primitive log not set -> pass state through       (no-op; voxy keeps its current behaviour)
 *
 * Three Block-class registrations cover the visible DT block surface:
 *   - BranchBlock.class       — branches, trunks, BasicRootsBlock (subclass)
 *   - SurfaceRootBlock.class  — above-ground roots; extends Block directly,
 *                               but exposes Family via getFamily()
 *   - TrunkShellBlock.class   — radius>8 outer trunk wrap; no species info,
 *                               falls back to a generic vanilla wood
 */
public final class DynamicTreesCompat {
    private static final String MODID = "dynamictrees";

    // log -> wood cache, populated lazily on first resolver hit. Pre-population at
    // init time used to fill this from ForgeRegistries.BLOCKS.getEntries(), but
    // that runs at RenderSystem.initRenderer — too early for modded blocks
    // registered via DeferredRegister (observed yielding only ~22 vanilla-ish
    // pairs out of 50+ expected). Lazy population at ingest time sees a fully
    // populated registry and catches every mod.
    private static final ConcurrentHashMap<Block, Block> LOG_TO_WOOD = new ConcurrentHashMap<>();
    // Marker for "we looked, no wood sibling exists" — distinguishes "uncached"
    // from "checked, definitively absent" so we don't re-query the registry on
    // every voxel of a log that lacks a wood form.
    private static final Block WOOD_MISS = Blocks.AIR;

    // Per-Block one-shot logging — keyed by the Block instance, so each unique
    // branch/root/shell block id logs exactly once. Earlier version keyed by
    // Block.class and missed the second-and-onward branch sharing a class
    // (e.g. dtru:eucalyptus_branch shares ThickBranchBlock.class with
    // dynamictrees:oak_branch and was silently suppressed).
    private static final Set<Block> LOGGED_BLOCKS = ConcurrentHashMap.newKeySet();

    private DynamicTreesCompat() {}

    public static void registerIfAvailable(VoxyStateProxyRegistry registry) {
        if (!isModLoaded()) return;

        registry.register(BranchBlock.class, BRANCH_RESOLVER);
        registry.register(SurfaceRootBlock.class, SURFACE_ROOT_RESOLVER);
        registry.register(TrunkShellBlock.class, TRUNK_SHELL_RESOLVER);
        Logger.info("Voxy/DT compat: registered BranchBlock + SurfaceRootBlock + TrunkShellBlock proxies"
                + " (log<->wood lookups resolve lazily at ingest time)");
    }

    /**
     * Mod-loaded check that works regardless of when in the FML lifecycle we
     * run. {@link ModList#get()} returns null until FML completes
     * construction, but voxy's init can fire as early as RenderSystem.initRenderer
     * (via MixinRenderSystem) — observed crashing with a null ModList in
     * practice. {@link LoadingModList} is populated much earlier and is what
     * voxy's existing MixinPlugin uses for the same reason. Try ModList first
     * (gives the canonical answer once construction is done) and fall back to
     * LoadingModList; either is correct, neither should throw.
     */
    private static boolean isModLoaded() {
        try {
            ModList ml = ModList.get();
            if (ml != null) return ml.isLoaded(MODID);
        } catch (Throwable ignored) {}
        try {
            LoadingModList lml = LoadingModList.get();
            if (lml != null) return lml.getModFileById(MODID) != null;
        } catch (Throwable ignored) {}
        return false;
    }

    private static final IStateProxyResolver BRANCH_RESOLVER = state -> {
        Block block = state.getBlock();
        if (!(block instanceof BranchBlock branch)) return state;
        BlockState out = resolveFromFamily(branch.getFamily(), state);
        logOnce(block, state, out, "BRANCH");
        return out;
    };

    /**
     * SurfaceRootBlock isn't a BranchBlock subclass (extends Block directly) but
     * carries its Family on a final field, so we can use the same log->wood
     * mapping path. Without this, visible above-ground roots bake from the
     * connection-aware model with no neighbour context and render incorrectly
     * (or invisibly) at LOD distance.
     */
    private static final IStateProxyResolver SURFACE_ROOT_RESOLVER = state -> {
        Block block = state.getBlock();
        if (!(block instanceof SurfaceRootBlock root)) return state;
        BlockState out = resolveFromFamily(root.getFamily(), state);
        logOnce(block, state, out, "SURFACE_ROOT");
        return out;
    };

    /**
     * TrunkShellBlock is the radius>8 outer wrap around thick-trunk cores. It
     * has no Family/species on the BlockState (DT renders bark by walking to a
     * per-position "muse" — the neighbouring BranchBlock core), and our
     * resolver interface is state-only by design.
     *
     * We substitute to AIR rather than a vanilla wood. Reason: at LOD distance
     * the shell wrap is the 8 voxels surrounding the 1-voxel core in a
     * radius>8 trunk's cross-section. Voxy's mipper picks the highest-opacity
     * non-air sub-voxel in each 2x2x2 aggregation cell, so if both shell and
     * core are solid wood the shell colour dominates (8 of 9 sub-voxels) and
     * the trunk reads as a fat, species-mismatched oak (or whatever wood we
     * picked) that abruptly terminates where the radius drops below 8.
     * Dropping the shell to air leaves the 1-voxel species-correct core as
     * the only non-air sub-voxel; the mipper picks it and the whole trunk
     * renders as a thin, consistent, species-correct column from base to
     * canopy, matching the natural-looking 1xN LOD silhouette we want.
     */
    private static final IStateProxyResolver TRUNK_SHELL_RESOLVER = state -> {
        BlockState out = Blocks.AIR.defaultBlockState();
        logOnce(state.getBlock(), state, out, "TRUNK_SHELL");
        return out;
    };

    private static void logOnce(Block in, BlockState inState, BlockState outState, String tag) {
        if (LOGGED_BLOCKS.add(in)) {
            String inId = ForgeRegistries.BLOCKS.getKey(in) + "";
            Block outBlock = outState.getBlock();
            String outId = ForgeRegistries.BLOCKS.getKey(outBlock) + "";
            Logger.info("Voxy/DT compat: " + tag + " resolver firing for "
                    + in.getClass().getName() + " (" + inId + ") -> " + outId);
        }
    }

    private static BlockState resolveFromFamily(Family family, BlockState fallback) {
        Optional<Block> primitiveLog = family.getPrimitiveLog();
        if (primitiveLog.isEmpty()) return fallback;
        Block log = primitiveLog.get();
        Block wood = lookupWood(log);
        return (wood != null ? wood : log).defaultBlockState();
    }

    // Returns the wood sibling for the given log, or null if there isn't one.
    // Tries the cache first; on miss, queries ForgeRegistries.BLOCKS and caches
    // the result (or WOOD_MISS sentinel) so the registry only gets hit once per
    // unique log block. Called at ingest time when the block registry is
    // definitely populated, so it catches mods that registered too late for
    // buildLogToWoodMap's init-time pass.
    private static Block lookupWood(Block log) {
        Block cached = LOG_TO_WOOD.get(log);
        if (cached != null) return cached == WOOD_MISS ? null : cached;
        ResourceLocation logId = ForgeRegistries.BLOCKS.getKey(log);
        if (logId == null) {
            LOG_TO_WOOD.put(log, WOOD_MISS);
            return null;
        }
        String woodPath = derivedWoodPath(logId.getPath());
        if (woodPath == null) {
            LOG_TO_WOOD.put(log, WOOD_MISS);
            return null;
        }
        ResourceLocation woodId = new ResourceLocation(logId.getNamespace(), woodPath);
        Block wood = ForgeRegistries.BLOCKS.getValue(woodId);
        if (wood == null || wood == Blocks.AIR) {
            LOG_TO_WOOD.put(log, WOOD_MISS);
            return null;
        }
        LOG_TO_WOOD.put(log, wood);
        return wood;
    }

    /**
     * Derive a wood sibling id from a log id by the naming convention vanilla
     * and every common addon follows:
     *   <ns>:<x>_log              -> <ns>:<x>_wood
     *   <ns>:stripped_<x>_log     -> <ns>:stripped_<x>_wood
     *   <ns>:<x>_stem             -> <ns>:<x>_hyphae               (nether)
     *   <ns>:stripped_<x>_stem    -> <ns>:stripped_<x>_hyphae      (nether)
     * Returns null for logs that don't match a pattern; lookupWood falls back
     * to the log itself for those.
     */
    private static String derivedWoodPath(String logPath) {
        // Order matters: check the longest / most specific suffix first so
        // "stripped_oak_log" doesn't get truncated as if it were just "_log".
        if (logPath.endsWith("_log"))  return strip(logPath, "_log")  + "_wood";
        if (logPath.endsWith("_stem")) return strip(logPath, "_stem") + "_hyphae";
        return null;
    }

    private static String strip(String s, String suffix) {
        return s.substring(0, s.length() - suffix.length());
    }
}
