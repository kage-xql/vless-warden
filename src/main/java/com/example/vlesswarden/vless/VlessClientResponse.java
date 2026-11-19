package com.example.vlesswarden.vless;

import java.time.Instant;
import java.util.UUID;

public record VlessClientResponse(
        UUID id,
        String name,
        boolean enabled,
        Instant createdAt,
        Long trafficLimitBytes,
        long trafficUsedBytes,
        Instant expiresAt,
        boolean configAvailable
) {
    static VlessClientResponse from(VlessClient client) {
        return new VlessClientResponse(
                client.getId(), client.getName(), client.isEnabled(), client.getCreatedAt(),
                client.getTrafficLimitBytes(), client.getTrafficUsedBytes(), client.getExpiresAt(),
                client.getEncryptedUuid() != null);
    }
}
