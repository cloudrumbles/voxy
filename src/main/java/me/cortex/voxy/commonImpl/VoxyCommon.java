package me.cortex.voxy.commonImpl;

import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.config.Serialization;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.api.distmarker.Dist;

public class VoxyCommon {
    // Non-final with safe defaults: the RenderSystem.initRenderer mixin can cause this
    // class to load before Forge has populated ModList, so the mod-info lookup must be
    // deferred to init() (called from VoxyMod's @Mod constructor).
    public static String MOD_VERSION = "<UNKNOWN>";
    public static boolean IS_DEDICATED_SERVER = false;
    public static boolean IS_IN_MINECRAFT = false;

    private static boolean initialized = false;

    public static void init() {
        if (initialized) return;
        initialized = true;
        try {
            var modList = ModList.get();
            var modInfo = modList == null ? null : modList.getModContainerById("voxy").orElse(null);
            if (modInfo == null) {
                Logger.error("VoxyCommon.init: could not resolve mod container for 'voxy' (ModList " + (modList == null ? "null" : "populated") + "); retaining defaults");
                return;
            }
            IS_IN_MINECRAFT = true;
            MOD_VERSION = modInfo.getModInfo().getVersion().toString();
            IS_DEDICATED_SERVER = FMLEnvironment.dist == Dist.DEDICATED_SERVER;
            Serialization.init();
        } catch (Throwable t) {
            Logger.error("VoxyCommon.init failed", t);
        }
    }

    //This is hardcoded like this because people do not understand what they are doing
    public static boolean isVerificationFlagOn(String name) {
        return isVerificationFlagOn(name, false);
    }

    public static boolean isVerificationFlagOn(String name, boolean defaultOn) {
        return System.getProperty("voxy."+name, defaultOn?"true":"false").equals("true");
    }

    public static void breakpoint() {
        int breakpoint = 0;
    }

    public interface IInstanceFactory {VoxyInstance create();}
    private static VoxyInstance INSTANCE;
    private static IInstanceFactory FACTORY = null;

    public static void setInstanceFactory(IInstanceFactory factory) {
        if (FACTORY != null) {
            throw new IllegalStateException("Cannot set instance factory more than once");
        }
        FACTORY = factory;
    }

    public static VoxyInstance getInstance() {
        return INSTANCE;
    }

    public static void shutdownInstance() {
        if (INSTANCE != null) {
            var instance = INSTANCE;
            INSTANCE = null;//Make it null before shutdown
            instance.shutdown();
        }
    }

    public static void createInstance() {
        if (FACTORY == null) {
            //Logger.info("Voxy factory");
            return;
        }
        if (INSTANCE != null) {
            throw new IllegalStateException("Cannot create multiple instances");
        }
        INSTANCE = FACTORY.create();
    }

    //Is voxy available in any capacity
    public static boolean isAvailable() {
        return FACTORY != null;
    }
}