package me.cortex.voxy.client.core.model.berbake;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-Block-class lookup of {@link IBerBakeHandler}s used by the model bakery to decide
 * whether a block-entity block should be baked from its real BlockEntityRenderer
 * (instead of the particle-cube fallback). Registered against a Block class; a state
 * whose Block is an instance of (or subclass of) that class routes through the handler.
 * The Block-class -> handler lookup is cached after first encounter.
 *
 * Deliberately parallel to {@link me.cortex.voxy.common.compat.VoxyStateProxyRegistry}
 * rather than part of it: that registry's resolver contract is pure state-only with no
 * BlockEntity access, which is the opposite of what a BER bake needs.
 *
 * Client-only (BER baking touches the render thread + GL); do not reference from common.
 */
public final class VoxyBerBakeRegistry {
    public static final VoxyBerBakeRegistry INSTANCE = new VoxyBerBakeRegistry();

    // Sentinel for "looked up, no handler" so the resolved cache can distinguish a miss
    // from a not-yet-computed entry (ConcurrentHashMap can't store null values).
    private static final IBerBakeHandler NONE = new IBerBakeHandler() {
        public net.minecraft.world.level.block.entity.BlockEntity createBlockEntity(
                net.minecraft.core.BlockPos pos, BlockState state, net.minecraft.world.level.Level level) { return null; }
        public void render(net.minecraft.world.level.block.entity.BlockEntity be,
                com.mojang.blaze3d.vertex.PoseStack pose, net.minecraft.client.renderer.MultiBufferSource buffers,
                int packedLight, int packedOverlay) {}
        public net.minecraft.resources.ResourceLocation atlas() { return null; }
    };

    private final Map<Class<? extends Block>, IBerBakeHandler> registered = new HashMap<>();
    // Registration by class NAME, resolved lazily — see VoxyMultiCellRegistry for the
    // rationale (mod block registries aren't populated during voxy client init, so a
    // ForgeRegistries lookup there returns AirBlock).
    private final Map<String, IBerBakeHandler> registeredByName = new HashMap<>();
    private final ConcurrentHashMap<Class<? extends Block>, IBerBakeHandler> resolved = new ConcurrentHashMap<>();

    public synchronized void register(Class<? extends Block> blockClass, IBerBakeHandler handler) {
        this.registered.put(blockClass, handler);
        this.resolved.clear();
    }

    /** Register by fully-qualified class name, resolved lazily against the runtime class. */
    public synchronized void register(String className, IBerBakeHandler handler) {
        this.registeredByName.put(className, handler);
        this.resolved.clear();
    }

    /** The handler for this state's block, or null if none is registered for its class hierarchy. */
    public IBerBakeHandler resolve(BlockState state) {
        Class<? extends Block> cls = state.getBlock().getClass();
        IBerBakeHandler handler = this.resolved.get(cls);
        if (handler == null) {
            handler = this.resolved.computeIfAbsent(cls, this::findHandler);
        }
        return handler == NONE ? null : handler;
    }

    private IBerBakeHandler findHandler(Class<? extends Block> cls) {
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            IBerBakeHandler h = this.registered.get(c);
            if (h != null) return h;
            IBerBakeHandler hn = this.registeredByName.get(c.getName());
            if (hn != null) return hn;
        }
        return NONE;
    }
}
