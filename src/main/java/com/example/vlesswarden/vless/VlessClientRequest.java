package com.example.vlesswarden.vless;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public record VlessClientRequest(
        @NotBlank @Size(max = 100) String name,
        @PositiveOrZero Long trafficLimitBytes,
        Instant expiresAt
) {
}
