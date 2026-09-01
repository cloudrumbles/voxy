package me.cortex.voxy.common.compat;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-Block-class lookup of {@link IStateProxyResolver}s applied at voxy ingest
 * time. Resolvers are registered against a Block class; an ingested state whose
 * Block is an instance of (or subclass of) that class routes through the
 * resolver. The Block-class -> resolver lookup is cached after first encounter.
 */
public class VoxyStateProxyRegistry {
    public static final VoxyStateProxyRegistry INSTANCE = new VoxyStateProxyRegistry();

    private static final IStateProxyResolver IDENTITY = s -> s;

    private final Map<Class<? extends Block>, IStateProxyResolver> registered = new HashMap<>();
    private final ConcurrentHashMap<Class<? extends Block>, IStateProxyResolver> resolved = new ConcurrentHashMap<>();

    public synchronized void register(Class<? extends Block> blockClass, IStateProxyResolver resolver) {
        this.registered.put(blockClass, resolver);
        // Drop the resolved cache so subsequent lookups pick up the new
        // registration (and don't return a previously-cached IDENTITY for
        // a class that now has a resolver in its hierarchy).
        this.resolved.clear();
    }

    public BlockState resolve(BlockState state) {
        Class<? extends Block> cls = state.getBlock().getClass();
        IStateProxyResolver resolver = this.resolved.get(cls);
        if (resolver == null) {
            resolver = this.resolved.computeIfAbsent(cls, this::findResolver);
        }
        return resolver.resolve(state);
    }

    private IStateProxyResolver findResolver(Class<? extends Block> cls) {
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            IStateProxyResolver r = this.registered.get(c);
            if (r != null) return r;
        }
        return IDENTITY;
    }
}
