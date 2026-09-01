package me.cortex.voxy.compat.create;

import me.cortex.voxy.client.core.model.berbake.IMultiCellHandler;
import me.cortex.voxy.client.core.model.berbake.KineticBerBakeHandler;
import me.cortex.voxy.client.core.model.berbake.VoxyBerBakeRegistry;
import me.cortex.voxy.client.core.model.berbake.VoxyMultiCellRegistry;
import me.cortex.voxy.common.Logger;

/**
 * Registers large rotating/kinetic block-entity blocks (Create + Create-ecosystem mods)
 * for multi-cell BER-bake LOD: their real BlockEntityRenderer is captured into the bakery,
 * its geometry bbox is measured, and overhang is stamped into neighbour cells so the block
 * renders at its true size at LOD instead of collapsing to one cube.
 *
 * Curated allowlist (from /voxyprobe over the full block registry): only blocks that are
 * (a) BER-rendered structures, (b) overflow their cell by >=~0.5 cell, (c) sample the
 * BLOCK atlas, and (d) bake deterministically (no animation/neighbour dependence — that
 * excludes mechanical_arm / chain_conveyor, whose captured bbox is degenerate under the
 * synthetic BakeLevel). Geometry alone is NOT the trigger: 11718 blocks overflow but are
 * leaves/furniture; multi-cell is opt-in via this list.
 *
 * Registration is by FULLY-QUALIFIED CLASS NAME, resolved lazily against the runtime block
 * class at first encounter. This runs during voxy client init — BEFORE mod block registries
 * are populated — so a ForgeRegistries lookup here would return AirBlock (the trap
 * DynamicTreesCompat documents). Names sidestep that. No-op for any name whose class never
 * loads (mod absent).
 *
 * NOTE: every entry below samples the BLOCK atlas (blocks.png), which KineticBerBakeHandler
 * binds. Block entities with a standalone entity texture (e.g. farm_and_charm:scarecrow ->
 * textures/entity/scarecrow.png) need arbitrary-texture binding and are deferred to that
 * generalisation (banners will likely need it too).
 */
public final class CreateBerBakeCompat {
    // Mod-loaded gate: any one of these present means there's kinetic content worth wiring.
    // Registration itself is harmless when a class is absent (the name just never matches).
    private static final String[] RELEVANT_MODS = { "create", "railways", "create_new_age" };

    // Large kinetic blocks: captured via their BER (block atlas), multi-cell expanded.
    // All confirmed by /voxyprobe to sample blocks.png and overflow >=0.5 cell.
    private static final String[] KINETIC_CLASSES = {
            // Create
            "com.simibubi.create.content.kinetics.waterwheel.WaterWheelBlock",
            "com.simibubi.create.content.kinetics.waterwheel.LargeWaterWheelBlock",
            "com.simibubi.create.content.kinetics.simpleRelays.CogWheelBlock",        // both cog sizes (small/large)
            "com.simibubi.create.content.kinetics.crusher.CrushingWheelBlock",
            "com.simibubi.create.content.kinetics.flywheel.FlywheelBlock",
            // Create: New Age
            "org.antarcticgardens.cna.content.electricity.generation.coil.GeneratorCoilBlock",
            // Steam 'n' Rails
            "com.railwayteam.railways.content.handcar.HandcarBlock",
            "com.railwayteam.railways.content.palettes.PalettesFlywheelBlock",        // 33 colour variants, one class
    };

    private CreateBerBakeCompat() {}

    public static void registerIfAvailable(VoxyBerBakeRegistry registry) {
        if (!anyModLoaded()) return;

        var berHandler = new KineticBerBakeHandler();
        // Multi-cell handler: a registered block expands only if its captured bbox actually
        // overflows the cell (stampMultiCell's gate). radius 2 covers a large wheel's reach.
        var multiCell = new IMultiCellHandler() {
            @Override public int maxFootprintRadius() { return 2; }
        };
        for (String cls : KINETIC_CLASSES) {
            registry.register(cls, berHandler);                  // BER capture (fixes cogs: was static->cube)
            VoxyMultiCellRegistry.INSTANCE.register(cls, multiCell);
        }
        Logger.info("Voxy/kinetic compat: registered " + KINETIC_CLASSES.length
                + " kinetic block class(es) [BER + multi-cell] by name (lazy-resolved)");
    }

    // Lifecycle-robust mod-loaded check (ModList may be null during early init -> fall back
    // to LoadingModList), matching DynamicTreesCompat.
    private static boolean anyModLoaded() {
        for (String modid : RELEVANT_MODS) {
            try {
                var ml = net.minecraftforge.fml.ModList.get();
                if (ml != null && ml.isLoaded(modid)) return true;
            } catch (Throwable ignored) {}
            try {
                var lml = net.minecraftforge.fml.loading.LoadingModList.get();
                if (lml != null && lml.getModFileById(modid) != null) return true;
            } catch (Throwable ignored) {}
        }
        return false;
    }
}
