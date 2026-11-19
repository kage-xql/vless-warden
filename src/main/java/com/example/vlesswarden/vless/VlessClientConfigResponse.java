package com.example.vlesswarden.vless;

import java.util.UUID;

public record VlessClientConfigResponse(UUID id, String name, boolean enabled, String uri) {
}
