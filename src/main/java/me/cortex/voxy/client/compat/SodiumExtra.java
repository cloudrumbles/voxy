package me.cortex.voxy.client.compat;

import me.cortex.voxy.commonImpl.ForgePlatform;

public class SodiumExtra {
    public static final boolean HAS_SODIUM_EXTRA = ForgePlatform.isModLoaded("sodium-extra")
            || ForgePlatform.isModLoaded("rubidium_extra")
            || ForgePlatform.isModLoaded("embeddium_extra");

    public static boolean useSodiumExtraCulling() {
        return HAS_SODIUM_EXTRA;
    }
}
