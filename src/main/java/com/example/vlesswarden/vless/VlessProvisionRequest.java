package com.example.vlesswarden.vless;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

public record VlessProvisionRequest(
        @Min(1) @Max(65535) Integer listenPort,
        @Size(max = 255) String dest,
        List<@Size(max = 255) String> serverNames,
        List<@Pattern(regexp = "[0-9a-fA-F]{0,16}") String> shortIds,
        @Pattern(regexp = "chrome|firefox|safari|ios|android|edge|random|randomized") String fingerprint,
        @Pattern(regexp = "xtls-rprx-vision|") String flow
) {
}
