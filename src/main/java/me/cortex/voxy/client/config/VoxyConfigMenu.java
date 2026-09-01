package me.cortex.voxy.client.config;

import com.google.common.collect.ImmutableList;
import me.cortex.voxy.client.VoxyClientInstance;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.client.core.util.IrisUtil;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.jellysquid.mods.sodium.client.gui.options.OptionFlag;
import me.jellysquid.mods.sodium.client.gui.options.OptionGroup;
import me.jellysquid.mods.sodium.client.gui.options.OptionImpact;
import me.jellysquid.mods.sodium.client.gui.options.OptionImpl;
import me.jellysquid.mods.sodium.client.gui.options.OptionPage;
import me.jellysquid.mods.sodium.client.gui.options.control.ControlValueFormatter;
import me.jellysquid.mods.sodium.client.gui.options.control.SliderControl;
import me.jellysquid.mods.sodium.client.gui.options.control.TickBoxControl;
import me.jellysquid.mods.sodium.client.gui.options.storage.OptionStorage;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.embeddedt.embeddium.api.OptionGUIConstructionEvent;
import org.embeddedt.embeddium.client.gui.options.OptionIdentifier;

// Registers a "Voxy" tab with Embeddium's options GUI. The original voxy
// targeted Sodium 0.8.x's ConfigEntryPoint API (which doesn't exist on
// Embeddium 0.3.x); this is a minimal re-implementation against
// Embeddium's own OptionGUIConstructionEvent bus.
public final class VoxyConfigMenu {
    private VoxyConfigMenu() {}

    public static void register() {
        OptionGUIConstructionEvent.BUS.addListener(VoxyConfigMenu::onConstructOptionsGUI);
    }

    private static void onConstructOptionsGUI(OptionGUIConstructionEvent event) {
        if (!VoxyCommon.isAvailable()) return;

        var storage = new VoxyOptionsStorage();

        // Deliberately NO REQUIRES_RENDERER_RELOAD flag: Embeddium would fire
        // LevelRenderer.allChanged() before storage.save() runs, triggering
        // voxy's renderer-reload hook while the instance is still null (emits
        // a spurious "Not creating renderer due to null instance" error).
        // VoxyOptionsStorage.save() below handles the instance lifecycle and
        // renderer attach/detach directly, in the correct order.
        var enabled = OptionImpl.createBuilder(boolean.class, storage)
                .setId(OptionIdentifier.create("voxy", "enabled", Boolean.class))
                .setName(Component.translatable("voxy.config.general.enabled"))
                .setTooltip(Component.translatable("voxy.config.general.enabled.tooltip"))
                .setBinding(
                        (c, v) -> c.enabled = v,
                        c -> c.enabled)
                .setControl(TickBoxControl::new)
                .setImpact(OptionImpact.HIGH)
                .build();

        var rendering = OptionImpl.createBuilder(boolean.class, storage)
                .setId(OptionIdentifier.create("voxy", "rendering", Boolean.class))
                .setName(Component.translatable("voxy.config.general.rendering"))
                .setTooltip(Component.translatable("voxy.config.general.rendering.tooltip"))
                .setBinding(
                        (c, v) -> c.enableRendering = v,
                        c -> c.enableRendering)
                .setControl(TickBoxControl::new)
                .setImpact(OptionImpact.HIGH)
                .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                .build();

        var ingest = OptionImpl.createBuilder(boolean.class, storage)
                .setId(OptionIdentifier.create("voxy", "ingest", Boolean.class))
                .setName(Component.translatable("voxy.config.general.ingest"))
                .setTooltip(Component.translatable("voxy.config.general.ingest.tooltip"))
                .setBinding(
                        (c, v) -> c.ingestEnabled = v,
                        c -> c.ingestEnabled)
                .setControl(TickBoxControl::new)
                .build();

        // sectionRenderDistance is stored as a float in top-level-LoD units;
        // expose it as an integer slider in chunk-equivalent steps.
        var renderDistance = OptionImpl.createBuilder(int.class, storage)
                .setId(OptionIdentifier.create("voxy", "render_distance", Integer.class))
                .setName(Component.translatable("voxy.config.general.renderDistance"))
                .setTooltip(Component.translatable("voxy.config.general.renderDistance.tooltip"))
                .setBinding(
                        (c, v) -> c.sectionRenderDistance = v / 16f,
                        c -> Math.round(c.sectionRenderDistance * 16))
                .setControl(o -> new SliderControl(o, 32, 1024, 16, v -> Component.literal(Integer.toString(v * 2))))
                .setImpact(OptionImpact.HIGH)
                .build();

        var environmentalFog = OptionImpl.createBuilder(boolean.class, storage)
                .setId(OptionIdentifier.create("voxy", "environmental_fog", Boolean.class))
                .setName(Component.translatable("voxy.config.general.environmental_fog"))
                .setTooltip(Component.translatable("voxy.config.general.environmental_fog.tooltip"))
                .setBinding(
                        (c, v) -> c.useEnvironmentalFog = v,
                        c -> c.useEnvironmentalFog)
                .setControl(TickBoxControl::new)
                .setImpact(OptionImpact.LOW)
                .build();

        // Reasonable upper bound covers desktop CPUs through ~32 cores. Voxy
        // reads this through updateDedicatedThreads() which subtracts sodium's
        // builder threads when "use sodium threads" is on.
        var serviceThreads = OptionImpl.createBuilder(int.class, storage)
                .setId(OptionIdentifier.create("voxy", "service_threads", Integer.class))
                .setName(Component.translatable("voxy.config.general.serviceThreads"))
                .setTooltip(Component.translatable("voxy.config.general.serviceThreads.tooltip"))
                .setBinding(
                        (c, v) -> c.serviceThreads = v,
                        c -> c.serviceThreads)
                .setControl(o -> new SliderControl(o, 1, 32, 1, v -> Component.literal(Integer.toString(v))))
                .setImpact(OptionImpact.MEDIUM)
                .build();

        // Inverted binding: the field is stored as "dont use" but the menu
        // presents the positive form to match the i18n label.
        var useSodiumBuilder = OptionImpl.createBuilder(boolean.class, storage)
                .setId(OptionIdentifier.create("voxy", "use_sodium_builder", Boolean.class))
                .setName(Component.translatable("voxy.config.general.useSodiumBuilder"))
                .setTooltip(Component.translatable("voxy.config.general.useSodiumBuilder.tooltip"))
                .setBinding(
                        (c, v) -> c.dontUseSodiumBuilderThreads = !v,
                        c -> !c.dontUseSodiumBuilderThreads)
                .setControl(TickBoxControl::new)
                .setImpact(OptionImpact.MEDIUM)
                .build();

        var hideUnrefinedLod = OptionImpl.createBuilder(boolean.class, storage)
                .setId(OptionIdentifier.create("voxy", "hide_unrefined_lod", Boolean.class))
                .setName(Component.translatable("voxy.config.general.hide_unrefined_lod"))
                .setTooltip(Component.translatable("voxy.config.general.hide_unrefined_lod.tooltip"))
                .setBinding(
                        (c, v) -> c.hideUnrefinedLodCells = v,
                        c -> c.hideUnrefinedLodCells)
                .setControl(TickBoxControl::new)
                .setImpact(OptionImpact.LOW)
                .build();

        var group = OptionGroup.createBuilder()
                .setId(OptionIdentifier.create("voxy", "general"))
                .add(enabled)
                .add(rendering)
                .add(ingest)
                .add(renderDistance)
                .add(environmentalFog)
                .add(serviceThreads)
                .add(useSodiumBuilder)
                .add(hideUnrefinedLod)
                .build();

        // Distant generation — Phase 1 SP overworld + Phase 2 multi-dim.
        // distantGenEnabled is the master toggle. All four knobs are live-
        // read at the appropriate tick paths, so runtime changes take effect
        // without a renderer reload or instance restart.
        var distantGenEnabled = OptionImpl.createBuilder(boolean.class, storage)
                .setId(OptionIdentifier.create("voxy", "distant_gen_enabled", Boolean.class))
                .setName(Component.translatable("voxy.config.distantgen.enabled"))
                .setTooltip(Component.translatable("voxy.config.distantgen.enabled.tooltip"))
                .setBinding(
                        (c, v) -> c.distantGenEnabled = v,
                        c -> c.distantGenEnabled)
                .setControl(TickBoxControl::new)
                .setImpact(OptionImpact.HIGH)
                .build();

        var distantGenRadius = OptionImpl.createBuilder(int.class, storage)
                .setId(OptionIdentifier.create("voxy", "distant_gen_radius", Integer.class))
                .setName(Component.translatable("voxy.config.distantgen.radius"))
                .setTooltip(Component.translatable("voxy.config.distantgen.radius.tooltip"))
                .setBinding(
                        (c, v) -> c.distantGenRadius = v,
                        c -> c.distantGenRadius)
                .setControl(o -> new SliderControl(o, 64, 512, 16, v -> Component.literal(v + " chunks")))
                .setImpact(OptionImpact.MEDIUM)
                .build();

        // Concurrency cap on in-flight chunk generation. Performance-critical
        // — higher values speed up fill but increase chunk-system pressure.
        // Empirical: on a typical machine 2 is comfortable, 8 starts noticeably
        // impacting tick time even when chunks are already in region files.
        var distantGenThreads = OptionImpl.createBuilder(int.class, storage)
                .setId(OptionIdentifier.create("voxy", "distant_gen_threads", Integer.class))
                .setName(Component.translatable("voxy.config.distantgen.threads"))
                .setTooltip(Component.translatable("voxy.config.distantgen.threads.tooltip"))
                .setBinding(
                        (c, v) -> c.distantGenThreads = v,
                        c -> c.distantGenThreads)
                .setControl(o -> new SliderControl(o, 1, 32, 1, v -> Component.literal(Integer.toString(v))))
                .setImpact(OptionImpact.HIGH)
                .build();

        var distantGenVerboseLogging = OptionImpl.createBuilder(boolean.class, storage)
                .setId(OptionIdentifier.create("voxy", "distant_gen_verbose_logging", Boolean.class))
                .setName(Component.translatable("voxy.config.distantgen.verbose_logging"))
                .setTooltip(Component.translatable("voxy.config.distantgen.verbose_logging.tooltip"))
                .setBinding(
                        (c, v) -> c.distantGenVerboseLogging = v,
                        c -> c.distantGenVerboseLogging)
                .setControl(TickBoxControl::new)
                .setImpact(OptionImpact.LOW)
                .build();

        var distantGenGroup = OptionGroup.createBuilder()
                .setId(OptionIdentifier.create("voxy", "distantgen"))
                .add(distantGenEnabled)
                .add(distantGenRadius)
                .add(distantGenThreads)
                .add(distantGenVerboseLogging)
                .build();

        var page = new OptionPage(
                OptionIdentifier.create("voxy", "main"),
                Component.translatable("voxy.config.title"),
                ImmutableList.of(group, distantGenGroup));

        event.addPage(page);
    }

    // Bridges Embeddium's OptionStorage contract to VoxyConfig. Embeddium calls
    // save() after an option's binding is applied; we react based on what
    // changed so that toggling "enabled" or reducing render distance takes
    // effect immediately instead of requiring a rejoin.
    private static final class VoxyOptionsStorage implements OptionStorage<VoxyConfig> {
        private final boolean wasEnabled = VoxyConfig.CONFIG.enabled;
        private final float wasSectionRenderDistance = VoxyConfig.CONFIG.sectionRenderDistance;
        private final int wasServiceThreads = VoxyConfig.CONFIG.serviceThreads;
        private final boolean wasDontUseSodiumBuilderThreads = VoxyConfig.CONFIG.dontUseSodiumBuilderThreads;

        @Override public VoxyConfig getData() { return VoxyConfig.CONFIG; }

        @Override
        public void save() {
            VoxyConfig.CONFIG.save();
            var mc = Minecraft.getInstance();
            var vrsh = mc == null ? null : (IGetVoxyRenderSystem) mc.levelRenderer;
            if (vrsh == null) return;

            if (wasEnabled != VoxyConfig.CONFIG.enabled) {
                if (VoxyConfig.CONFIG.enabled) {
                    if (VoxyClientInstance.isInGame && VoxyCommon.getInstance() == null) {
                        VoxyCommon.createInstance();
                    }
                    vrsh.createRenderer();
                } else {
                    vrsh.shutdownRenderer();
                    VoxyCommon.shutdownInstance();
                }
                IrisUtil.reload();
            } else if (wasSectionRenderDistance != VoxyConfig.CONFIG.sectionRenderDistance) {
                var vrs = vrsh.getVoxyRenderSystem();
                if (vrs != null) vrs.setRenderDistance(VoxyConfig.CONFIG.sectionRenderDistance);
            }

            // Thread-pool sizing reacts at runtime via updateDedicatedThreads().
            if (wasServiceThreads != VoxyConfig.CONFIG.serviceThreads
                    || wasDontUseSodiumBuilderThreads != VoxyConfig.CONFIG.dontUseSodiumBuilderThreads) {
                var instance = (VoxyClientInstance) VoxyCommon.getInstance();
                if (instance != null) {
                    instance.updateDedicatedThreads();
                }
            }
            // useEnvironmentalFog is re-read each frame by MixinFogRenderer, no
            // action needed here.
        }
    }
}
