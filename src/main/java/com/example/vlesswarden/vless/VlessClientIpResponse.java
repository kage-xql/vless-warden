package com.example.vlesswarden.vless;

import java.time.Instant;

public record VlessClientIpResponse(
        String ip,
        Instant firstSeen,
        Instant lastSeen,
        boolean blocked,
        Instant blockedUntil
) {
    static VlessClientIpResponse from(VlessClientIp value, Instant now) {
        boolean blocked = value.getBlockedUntil() != null && value.getBlockedUntil().isAfter(now);
        return new VlessClientIpResponse(value.getIp(), value.getFirstSeen(), value.getLastSeen(),
                blocked, blocked ? value.getBlockedUntil() : null);
    }
}
