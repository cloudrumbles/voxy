package me.cortex.voxy.commonImpl;

import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.config.Serialization;

public final class VoxyCommon {
    public static final String MOD_VERSION;
    public static final boolean IS_DEDICATED_SERVER;
    public static final boolean IS_IN_MINECRAFT;

    static {
        IS_IN_MINECRAFT = ForgePlatform.isModLoaded("voxy");
        IS_DEDICATED_SERVER = ForgePlatform.isDedicatedServer();
        MOD_VERSION = ForgePlatform.modVersion("voxy").orElse("<UNKNOWN>");

        if (IS_IN_MINECRAFT) {
            Serialization.init();
        } else {
            Logger.error("Running voxy without minecraft");
        }
    }

    private VoxyCommon() {
    }

    /** Forces the common bootstrap to run from the Forge mod constructor. */
    public static void initialize() {
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
            return;
        }
        if (INSTANCE != null) {
            throw new IllegalStateException("Cannot create multiple instances");
        }
        var instance = FACTORY.create();
        if (instance != null) {
            INSTANCE = instance;
        }
    }

    //Is voxy available in any capacity
    public static boolean isAvailable() {
        return FACTORY != null;
    }

    public static final boolean IS_MINE_IN_ABYSS = false;
}
