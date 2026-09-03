package me.cortex.voxy.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import org.junit.jupiter.api.Test;

final class ServerStorageKeyTest {
    @Test
    void configuredAddressPreservesTheHistoricalHostPortLayout() {
        assertEquals("example.org_25565", ServerStorageKey.fromConfiguredAddress("example.org:25565"));
    }

    @Test
    void directConnectionAddressIsStableAcrossRestarts() {
        InetSocketAddress address = InetSocketAddress.createUnresolved("Example.Org", 25565);
        assertEquals("Example.Org_25565", ServerStorageKey.fromRemoteAddress(address));
        assertEquals(
                ServerStorageKey.fromRemoteAddress(address),
                ServerStorageKey.fromRemoteAddress(InetSocketAddress.createUnresolved("Example.Org", 25565)));
    }

    @Test
    void unsafeCharactersCannotEscapeTheMultiplayerStorageRoot() {
        String key = ServerStorageKey.fromConfiguredAddress("../servers/../../evil\\name:25565");
        assertFalse(key.contains("/"));
        assertFalse(key.contains("\\"));
        assertNotEquals(".", key);
        assertNotEquals("..", key);
    }

    @Test
    void lossyAddressesCannotCollapseOntoTheSameDirectory() {
        String slash = ServerStorageKey.fromConfiguredAddress("server/a:25565");
        String backslash = ServerStorageKey.fromConfiguredAddress("server\\a:25565");
        String unicode = ServerStorageKey.fromConfiguredAddress("சேவையகம்:25565");

        assertNotEquals(slash, backslash);
        assertNotEquals(slash, unicode);
        assertNotEquals(backslash, unicode);
    }

    @Test
    void windowsDeviceNamesAndTrailingDotsAreSafe() {
        assertEquals("_CON", ServerStorageKey.fromConfiguredAddress("CON"));
        assertEquals("_lpt9.log", ServerStorageKey.fromConfiguredAddress("lpt9.log"));
        assertFalse(ServerStorageKey.fromConfiguredAddress("example.org.").endsWith("."));
    }

    @Test
    void longAddressesRemainBoundedAndCollisionResistant() {
        String prefix = "very-long-server-name-".repeat(12);
        String first = ServerStorageKey.fromConfiguredAddress(prefix + "a:25565");
        String second = ServerStorageKey.fromConfiguredAddress(prefix + "b:25565");

        assertTrue(first.length() <= 120);
        assertTrue(second.length() <= 120);
        assertNotEquals(first, second);
    }

    @Test
    void missingAddressesStayMissingInsteadOfSharingAKey() {
        assertNull(ServerStorageKey.fromConfiguredAddress(null));
        assertNull(ServerStorageKey.fromConfiguredAddress("   "));
        assertNull(ServerStorageKey.fromRemoteAddress(null));
    }
}
