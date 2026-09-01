package me.cortex.voxy.client;

import me.cortex.voxy.client.core.gl.Capabilities;
import me.cortex.voxy.client.core.rendering.util.SharedIndexBuffer;
import me.cortex.voxy.client.core.util.ExpansionUtil;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.minecraft.client.Minecraft;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileLock;
import java.nio.channels.NonWritableChannelException;

public final class VoxyClient {
    private static FileLock EXCLUSIVE_LOCK;
    private static boolean INSTANCE_FACTORY_SET;
    private static boolean RENDER_BACKEND_READY;
    private static boolean RENDER_BACKEND_INITIALIZED;

    private static VoxyClientInstance createInstance() {
        if (!RENDER_BACKEND_READY) {
            Logger.error("Voxy render backend is not initialized");
            return null;
        }
        return new VoxyClientInstance();
    }

    private static void setInstanceFactory() {
        if (!INSTANCE_FACTORY_SET) {
            VoxyCommon.setInstanceFactory(VoxyClient::createInstance);
            INSTANCE_FACTORY_SET = true;
        }
        VoxyConfig.reloadAfterVoxyAvailable();
    }

    public static void initVoxyClient() {
        if (RENDER_BACKEND_INITIALIZED) {
            return;
        }
        RENDER_BACKEND_INITIALIZED = true;
        setInstanceFactory();

        Capabilities.init();//Ensure clinit is called

        if (Capabilities.INSTANCE.hasBrokenDepthSampler) {
            Logger.error("AMD broken depth sampler detected, voxy does not work correctly and has been disabled, this will hopefully be fixed in the future");
        }

        boolean systemSupported = Capabilities.INSTANCE.compute && Capabilities.INSTANCE.indirectParameters && !Capabilities.INSTANCE.hasBrokenDepthSampler;
        if (!systemSupported) {
             Logger.error("Voxy is unsupported on your system.");
        }

        if (systemSupported && System.getProperty("voxy.exclusiveLock", "false").equalsIgnoreCase("true")) {
            //Try acquire the lock file
            var vf = Minecraft.getInstance().gameDirectory.toPath().resolve(".voxy");
            if (!vf.toFile().isDirectory()) {
                vf.toFile().mkdir();
            }
            try {
                FileOutputStream fis = new FileOutputStream(vf.resolve("voxy.lock").toFile());
                EXCLUSIVE_LOCK = fis.getChannel().lock(0, Long.MAX_VALUE, false);
            } catch (NonWritableChannelException | IOException e) {
                Logger.error("Failed to acquire exclusive voxy lock file, mod will be disabled");
                systemSupported = false;
            }

        }

        if (systemSupported) {

            SharedIndexBuffer.INSTANCE.id();
            RENDER_BACKEND_READY = true;

            if (!Capabilities.INSTANCE.subgroup) {
                Logger.warn("GPU does not support subgroup operations, expect some performance degradation");
            }

        }

        if (!ExpansionUtil.isJava21()) {
            Logger.warn("Cannot use native Integer/Long compression. Using fallback...");
        }
    }

    public static void onInitializeClient() {
        setInstanceFactory();
    }

    public static boolean isRenderBackendInitialized() {
        return RENDER_BACKEND_INITIALIZED;
    }

    public static boolean isRenderBackendReady() {
        return RENDER_BACKEND_READY;
    }

    public static boolean isFrexActive() {
        return org.embeddedt.embeddium.util.sodium.FlawlessFrames.isActive();
    }

    public static int getOcclusionDebugState() {
        return 0;
    }

    public static boolean disableSodiumChunkRender() {
        return false;// getOcclusionDebugState() != 0;
    }
}
