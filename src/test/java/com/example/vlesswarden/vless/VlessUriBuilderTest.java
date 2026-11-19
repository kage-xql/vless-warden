package com.example.vlesswarden.vless;

import com.example.vlesswarden.server.Server;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VlessUriBuilderTest {
    private final VlessUriBuilder builder = new VlessUriBuilder();

    @Test
    void buildsRealityUriWithPasswordAndQuery() {
        Server server = new Server(UUID.randomUUID(), "node", "vpn.example.com", 22, "root", new byte[]{1});
        VlessInbound inbound = inbound("vpn.example.com:443");
        VlessClient client = new VlessClient(inbound.getId(), "Laptop", "vw_" + "a".repeat(32), new byte[]{1});

        String uri = builder.uri(server, inbound, client, "8e4f4e2c-5e0e-4b32-9a5e-2f9f4c6d7a11");

        assertTrue(uri.startsWith("vless://8e4f4e2c-5e0e-4b32-9a5e-2f9f4c6d7a11@vpn.example.com:443?"));
        assertTrue(uri.contains("encryption=none"));
        assertTrue(uri.contains("security=reality"));
        assertTrue(uri.contains("sni=www.microsoft.com"));
        assertTrue(uri.contains("flow=xtls-rprx-vision"));
        assertTrue(uri.endsWith("#Laptop"));
    }

    @Test
    void bracketsIpv6LiteralHost() {
        Server server = new Server(UUID.randomUUID(), "node", "2001:db8::1", 22, "root", new byte[]{1});
        VlessInbound inbound = inbound("www.microsoft.com:443");
        VlessClient client = new VlessClient(inbound.getId(), "Phone", "vw_" + "b".repeat(32), new byte[]{1});

        String uri = builder.uri(server, inbound, client, "8e4f4e2c-5e0e-4b32-9a5e-2f9f4c6d7a11");

        assertTrue(uri.contains("@[2001:db8::1]:443?"));
    }

    @Test
    void emptyFlowIsOmitted() {
        Server server = new Server(UUID.randomUUID(), "node", "vpn.example.com", 22, "root", new byte[]{1});
        VlessInbound inbound = new VlessInbound(UUID.randomUUID(), "vless-warden", 443,
                "www.microsoft.com:443", "www.microsoft.com", "01", "public", new byte[]{1}, "chrome", "");
        VlessClient client = new VlessClient(inbound.getId(), "Laptop", "vw_" + "c".repeat(32), new byte[]{1});

        String uri = builder.uri(server, inbound, client, "8e4f4e2c-5e0e-4b32-9a5e-2f9f4c6d7a11");

        assertTrue(!uri.contains("flow="));
    }

    private VlessInbound inbound(String dest) {
        return new VlessInbound(UUID.randomUUID(), "vless-warden", 443, dest,
                "www.microsoft.com", "0123456789abcdef", "public-key", new byte[]{1}, "chrome",
                "xtls-rprx-vision");
    }
}
