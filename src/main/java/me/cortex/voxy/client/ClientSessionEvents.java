package me.cortex.voxy.client;

import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.commonImpl.VoxyCommon;

import java.net.SocketAddress;

public class ClientSessionEvents {
    public static boolean inSession = false;
    private static String serverStorageKey;

    public static void sessionStart() {
        sessionStart(null);
    }

    public static void sessionStart(SocketAddress remoteAddress) {
        if (inSession) {
            throw new IllegalStateException("Cannot start new session while in a session");
        }
        if (VoxyCommon.getInstance() != null) {
            throw new IllegalStateException("Cannot start a session while a Voxy instance exists");
        }

        serverStorageKey = ServerStorageKey.fromRemoteAddress(remoteAddress);
        inSession = true;
        try {
            if (VoxyCommon.isAvailable() && VoxyConfig.CONFIG.enabled) {
                VoxyCommon.createInstance();
            }
        } catch (RuntimeException | Error exception) {
            inSession = false;
            serverStorageKey = null;
            throw exception;
        }
    }

    static String getServerStorageKey() {
        return serverStorageKey;
    }

    public static void sessionEnd() {
        if (!inSession) {
            throw new IllegalStateException("Cannot end a session while not in a session");
        }
        inSession = false;

        try {
            VoxyCommon.shutdownInstance();
        } finally {
            serverStorageKey = null;
        }
    }
}
