package me.cortex.voxy.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class ServerStorageKeyIpv6Test {
    @Test
    void conventionalHostPortKeepsHistoricalLayout() {
        assertEquals("example.org_25565", ServerStorageKey.fromConfiguredAddress("example.org:25565"));
        assertEquals("127.0.0.1_25565", ServerStorageKey.fromConfiguredAddress("127.0.0.1:25565"));
    }

    @Test
    void ipv6AndLossyEndpointNamesRemainDistinctAndBounded() {
        String loopback = ServerStorageKey.fromConfiguredAddress("[::1]:25565");
        String expandedLoopback = ServerStorageKey.fromConfiguredAddress("[0:0:0:0:0:0:0:1]:25565");
        String differentPort = ServerStorageKey.fromConfiguredAddress("[::1]:25566");
        String slash = ServerStorageKey.fromConfiguredAddress("server/a:25565");
        String backslash = ServerStorageKey.fromConfiguredAddress("server\\a:25565");

        assertNotEquals(loopback, expandedLoopback);
        assertNotEquals(loopback, differentPort);
        assertNotEquals(slash, backslash);
        assertTrue(loopback.length() <= 120);
        assertTrue(expandedLoopback.length() <= 120);
    }
}
