package me.cortex.voxy.client;

import me.cortex.voxy.client.core.gl.Capabilities;
import me.cortex.voxy.client.core.model.bakery.BudgetBufferRenderer;
import me.cortex.voxy.client.core.model.berbake.BannerBerBakeHandler;
import me.cortex.voxy.client.core.model.berbake.ChestBerBakeHandler;
import me.cortex.voxy.client.core.model.berbake.IMultiCellHandler;
import me.cortex.voxy.client.core.model.berbake.VoxyBerBakeRegistry;
import me.cortex.voxy.client.core.model.berbake.VoxyMultiCellRegistry;
import me.cortex.voxy.client.core.rendering.util.SharedIndexBuffer;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.compat.VoxyCompatBootstrap;
import net.minecraft.world.level.block.AbstractBannerBlock;
import net.minecraft.world.level.block.AbstractChestBlock;

public class VoxyClient {
    public static void initVoxyClient() {
        // Wire per-mod state-proxy resolvers (currently: Dynamic Trees branch
        // -> primitive wood substitution). Each compat module no-ops when its
        // mod isn't loaded, so this is safe regardless of mod set.
        VoxyCompatBootstrap.init();

        // Bake chests (and any mod chest extending AbstractChestBlock) from their real
        // BlockEntityRenderer instead of the oak-plank particle cube. Geometry is fully
        // BlockState-determined; unsafe renderers degrade to the cube via BakeLevel's
        // throw-gate. AbstractChestBlock is vanilla, so this is unconditional.
        VoxyBerBakeRegistry.INSTANCE.register(AbstractChestBlock.class, new ChestBerBakeHandler());

        // Bake Create kinetic blocks (water wheels, cogwheels) from their real BER instead
        // of a plank cube. Single-cell capture (clipped at the cell edge); the determinism
        // gate verifies neighbour-independence, unsafe states degrade to the cube. No-op
        // when Create is absent (resolves target blocks reflectively from the registry).
        me.cortex.voxy.compat.create.CreateBerBakeCompat.registerIfAvailable(VoxyBerBakeRegistry.INSTANCE);

        // Bake banners (vanilla, all colours, standing + wall) from their real BER, tinted
        // by the banner's DyeColor (the cloth captures white; the bakery applies the dye as
        // a constant tint). Multi-cell so the ~2-cell-tall cloth renders at its true height
        // instead of vanishing (banners are RenderShape.INVISIBLE -> skipped at LOD today).
        // Patterns are unrecoverable without the BlockEntity; base colour + shape only.
        VoxyBerBakeRegistry.INSTANCE.register(AbstractBannerBlock.class, new BannerBerBakeHandler());
        VoxyMultiCellRegistry.INSTANCE.register(AbstractBannerBlock.class, new IMultiCellHandler() {
            @Override public int maxFootprintRadius() { return 2; }
        });

        Capabilities.init();//Ensure clinit is called

        if (Capabilities.INSTANCE.hasBrokenDepthSampler) {
            throw new RuntimeException("Voxy refuses to launch: AMD broken depth sampler driver bug detected. " +
                    "Voxy cannot render correctly on this GPU/driver combination. " +
                    "Remove voxy from the mods folder to launch Minecraft.");
        }

        if (!Capabilities.INSTANCE.compute || !Capabilities.INSTANCE.indirectParameters) {
            throw new RuntimeException("Voxy refuses to launch: GPU does not support required features (compute shaders + ARB_indirect_parameters). " +
                    "Remove voxy from the mods folder to launch Minecraft.");
        }

        SharedIndexBuffer.INSTANCE.id();
        BudgetBufferRenderer.init();

        VoxyCommon.setInstanceFactory(VoxyClientInstance::new);

        if (!Capabilities.INSTANCE.subgroup) {
            Logger.warn("GPU does not support subgroup operations, expect some performance degradation");
        }
    }
}