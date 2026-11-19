package com.example.vlesswarden.vless;

import java.util.List;

/**
 * Клиент Xray в том виде, в котором он попадает в config.json.
 */
public record XrayClient(String id, String email, String flow) {
    static List<XrayClient> of(List<VlessClient> clients, java.util.function.Function<VlessClient, String> uuid) {
        return clients.stream()
                .map(client -> new XrayClient(uuid.apply(client), client.getEmail(), VlessInbound.DEFAULT_FLOW))
                .toList();
    }
}
