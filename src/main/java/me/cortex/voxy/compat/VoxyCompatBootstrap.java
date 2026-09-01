package me.cortex.voxy.compat;

import me.cortex.voxy.common.compat.VoxyStateProxyRegistry;
import me.cortex.voxy.compat.dynamictrees.DynamicTreesCompat;

/**
 * Single entry point that wires every per-mod compat module into the
 * shared {@link VoxyStateProxyRegistry}. Each module's
 * registerIfAvailable() is a no-op when its target mod is absent (via
 * ModList.isLoaded), so this is safe to call unconditionally.
 */
public final class VoxyCompatBootstrap {
    private static boolean initialised = false;

    private VoxyCompatBootstrap() {}

    public static synchronized void init() {
        if (initialised) return;
        initialised = true;

        DynamicTreesCompat.registerIfAvailable(VoxyStateProxyRegistry.INSTANCE);
    }
}
