package com.example.vlesswarden.vless;

import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public record VlessClientUpdateRequest(
        @Size(max = 100) String name,
        Boolean enabled,
        @PositiveOrZero Long trafficLimitBytes,
        Boolean clearTrafficLimit,
        Instant expiresAt,
        Boolean clearExpiresAt,
        Boolean rotateUuid
) {
}
