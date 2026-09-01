package me.cortex.voxy.client.core.model.berbake;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-Block-class lookup of {@link IMultiCellHandler}s. Identical structure and cost model
 * to {@link VoxyBerBakeRegistry} / VoxyStateProxyRegistry: a small registration HashMap
 * walked once per Block class (superclass chain), then a ConcurrentHashMap cache so steady
 * state is a single O(1) lookup.
 *
 * A null result means "not a multi-cell block" — the overwhelming common case, so the
 * lookup must be cheap. Client-only (multi-cell expansion is a render/mesh concern).
 */
public final class VoxyMultiCellRegistry {
    public static final VoxyMultiCellRegistry INSTANCE = new VoxyMultiCellRegistry();

    private static final IMultiCellHandler NONE = new IMultiCellHandler() {
        public boolean shouldExpand(BlockState s) { return false; }
        public int maxFootprintRadius() { return 0; }
    };

    private final Map<Class<? extends Block>, IMultiCellHandler> registered = new HashMap<>();
    // Registration by fully-qualified class NAME, resolved lazily at resolve() time against
    // the real runtime block class. This avoids the empty-registry trap: voxy client init
    // runs before mod block registries are populated, so ForgeRegistries lookups there
    // return AirBlock (see DynamicTreesCompat's lazy-resolution note). Registering names
    // sidesteps any registry/class lookup at init. Matched against the block's own class
    // and its superclass chain.
    private final Map<String, IMultiCellHandler> registeredByName = new HashMap<>();
    private final ConcurrentHashMap<Class<? extends Block>, IMultiCellHandler> resolved = new ConcurrentHashMap<>();

    // Volatile flag so the mesh-time gate can early-out with a single read when no
    // multi-cell handler is registered (the common case, incl. all of M3). Avoids any
    // per-section scan/copy until a handler actually exists.
    private volatile boolean anyRegistered = false;

    public synchronized void register(Class<? extends Block> blockClass, IMultiCellHandler handler) {
        this.registered.put(blockClass, handler);
        this.resolved.clear();
        this.anyRegistered = true;
    }

    /**
     * Register by fully-qualified class name, resolved lazily at {@link #resolve} time.
     * Use this for modded blocks: the mod's block registry is not yet populated during
     * voxy client init, so resolving the Class there yields AirBlock. The name is matched
     * against the runtime block's own class and its superclasses.
     */
    public synchronized void register(String className, IMultiCellHandler handler) {
        this.registeredByName.put(className, handler);
        this.resolved.clear();
        this.anyRegistered = true;
    }

    /** True once any handler is registered. Cheap gate read for the mesh-time stamp hook. */
    public boolean hasAnyRegistered() {
        return this.anyRegistered;
    }

    /** The handler for this state's block, or null if none registered for its class hierarchy. */
    public IMultiCellHandler resolve(BlockState state) {
        Class<? extends Block> cls = state.getBlock().getClass();
        IMultiCellHandler handler = this.resolved.get(cls);
        if (handler == null) {
            handler = this.resolved.computeIfAbsent(cls, this::findHandler);
        }
        return handler == NONE ? null : handler;
    }

    private IMultiCellHandler findHandler(Class<? extends Block> cls) {
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            IMultiCellHandler h = this.registered.get(c);
            if (h != null) return h;
            IMultiCellHandler hn = this.registeredByName.get(c.getName());
            if (hn != null) return hn;
        }
        return NONE;
    }
}
