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
        boolean configAvailable,
        Integer ipLimit,
        int allowedIpCount,
        int blockedIpCount,
        boolean ipLimitExceeded
) {
    static VlessClientResponse from(VlessClient client) {
        boolean exceeded = client.getIpLimit() != null
                && (client.getAllowedIpCount() > client.getIpLimit() || client.getBlockedIpCount() > 0);
        return new VlessClientResponse(
                client.getId(), client.getName(), client.isEnabled(), client.getCreatedAt(),
                client.getTrafficLimitBytes(), client.getTrafficUsedBytes(), client.getExpiresAt(),
                client.getEncryptedUuid() != null,
                client.getIpLimit(), client.getAllowedIpCount(), client.getBlockedIpCount(), exceeded);
    }
}
