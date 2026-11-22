package com.example.vlesswarden.vless;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VlessIpLimitServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void buildsRulesPerClient() throws Exception {
        UUID clientId = UUID.fromString("11111111-2222-3333-4444-555555555555");
        Map<UUID, String> emails = Map.of(clientId, "vw_0123456789abcdef0123456789abcdef");
        Map<UUID, List<String>> bans = Map.of(clientId, List.of("1.2.3.4", "2001:db8:0:0::/64"));

        JsonNode root = mapper.readTree(VlessIpLimitService.routingRules(bans, emails, mapper));
        JsonNode rules = root.at("/routing/rules");
        assertEquals(1, rules.size());
        JsonNode rule = rules.get(0);
        assertEquals("field", rule.at("/type").asText());
        assertEquals("vw_ip_" + clientId.toString().replace("-", ""), rule.at("/ruleTag").asText());
        assertEquals("vw_0123456789abcdef0123456789abcdef", rule.at("/user/0").asText());
        assertEquals("1.2.3.4", rule.at("/source/0").asText());
        assertEquals("2001:db8:0:0::/64", rule.at("/source/1").asText());
        assertEquals("blocked", rule.at("/outboundTag").asText());
    }

    @Test
    void emptyBansProduceEmptyRules() throws Exception {
        JsonNode root = mapper.readTree(VlessIpLimitService.routingRules(Map.of(), Map.of(), mapper));
        assertEquals(0, root.at("/routing/rules").size());
    }
}
