package me.cortex.voxy.client;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/**
 * Produces one stable, filesystem-safe directory name for a multiplayer
 * endpoint. The common host:port form intentionally keeps Voxy's historical
 * layout (for example, example.org:25565 becomes example.org_25565).
 */
final class ServerStorageKey {
    private static final int MAX_KEY_LENGTH = 120;
    private static final int READABLE_PREFIX_LENGTH = 96;

    private ServerStorageKey() {
    }

    static String fromRemoteAddress(SocketAddress address) {
        if (address == null) {
            return null;
        }

        if (address instanceof InetSocketAddress inetAddress) {
            String host = inetAddress.getHostString();
            if ((host == null || host.isBlank()) && inetAddress.getAddress() != null) {
                host = inetAddress.getAddress().getHostAddress();
            }
            if (host == null || host.isBlank()) {
                return null;
            }
            return sanitize(host + ":" + inetAddress.getPort());
        }

        return sanitize(address.toString());
    }

    static String fromConfiguredAddress(String address) {
        return sanitize(address);
    }

    static String sanitize(String address) {
        if (address == null) {
            return null;
        }

        String value = address.trim();
        if (value.isEmpty()) {
            return null;
        }

        StringBuilder key = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (isAsciiLetterOrDigit(character)
                    || character == '.'
                    || character == '-'
                    || character == '_') {
                key.append(character);
            } else {
                key.append('_');
            }
        }

        String result = key.toString();
        if (result.equals(".") || result.equals("..") || isWindowsReservedName(result)) {
            result = "_" + result;
        }
        if (result.length() > MAX_KEY_LENGTH) {
            result = result.substring(0, READABLE_PREFIX_LENGTH)
                    + "-"
                    + sha256(value).substring(0, 16);
        }
        return result;
    }

    private static boolean isAsciiLetterOrDigit(char character) {
        return character >= 'a' && character <= 'z'
                || character >= 'A' && character <= 'Z'
                || character >= '0' && character <= '9';
    }

    private static boolean isWindowsReservedName(String value) {
        String basename = value;
        int extension = basename.indexOf('.');
        if (extension >= 0) {
            basename = basename.substring(0, extension);
        }
        String upper = basename.toUpperCase(Locale.ROOT);
        if (upper.equals("CON") || upper.equals("PRN") || upper.equals("AUX") || upper.equals("NUL")) {
            return true;
        }
        if (upper.length() == 4) {
            String prefix = upper.substring(0, 3);
            char suffix = upper.charAt(3);
            return (prefix.equals("COM") || prefix.equals("LPT")) && suffix >= '1' && suffix <= '9';
        }
        return false;
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte element : digest) {
                hex.append(Character.forDigit((element >>> 4) & 0xF, 16));
                hex.append(Character.forDigit(element & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
