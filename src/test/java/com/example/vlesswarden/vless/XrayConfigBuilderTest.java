package com.example.vlesswarden.vless;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class XrayConfigBuilderTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final XrayConfigBuilder builder = new XrayConfigBuilder(mapper);

    @Test
    void buildsRealityInboundWithStatsAndClients() throws Exception {
        VlessInbound inbound = new VlessInbound(UUID.randomUUID(), "vless-warden", 443,
                "www.microsoft.com:443", "www.microsoft.com,www.example.com", "0123456789abcdef",
                "public-key", new byte[]{1}, "chrome", "xtls-rprx-vision");

        String json = builder.config(inbound, "private-key", List.of(
                new XrayClient("uuid-1", "vw_first", "xtls-rprx-vision"),
                new XrayClient("uuid-2", "vw_second", "")));

        JsonNode root = mapper.readTree(json);
        assertEquals("127.0.0.1:10085", root.at("/api/listen").asText());
        assertTrue(root.at("/policy/levels/0/statsUserUplink").asBoolean());
        JsonNode inboundNode = root.at("/inbounds/0");
        assertEquals("raw", inboundNode.at("/streamSettings/network").asText());
        assertEquals("reality", inboundNode.at("/streamSettings/security").asText());
        assertEquals("www.microsoft.com:443", inboundNode.at("/streamSettings/realitySettings/target").asText());
        assertEquals(2, inboundNode.at("/settings/clients").size());
        assertEquals("vw_first", inboundNode.at("/settings/clients/0/email").asText());
        assertFalse(inboundNode.at("/settings/clients/1/flow").isTextual());
    }

    @Test
    void userConfigContainsSingleInbound() throws Exception {
        VlessInbound inbound = new VlessInbound(UUID.randomUUID(), "vless-warden", 443,
                "www.microsoft.com:443", "www.microsoft.com", "0123456789abcdef",
                "public-key", new byte[]{1}, "chrome", "xtls-rprx-vision");

        JsonNode root = mapper.readTree(builder.userConfig(inbound, "private-key",
                new XrayClient("uuid-1", "vw_first", "xtls-rprx-vision")));

        assertEquals("vless-warden", root.at("/inbounds/0/tag").asText());
        assertEquals(1, root.at("/inbounds/0/settings/clients").size());
    }
}
