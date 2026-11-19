package com.example.vlesswarden.vless;

import java.util.UUID;

public record VlessInboundResponse(
        UUID id,
        String tag,
        int listenPort,
        String dest,
        String serverNames,
        String shortIds,
        String publicKey,
        String fingerprint,
        String flow,
        boolean enabled,
        String status,
        String lastError,
        String xrayVersion
) {
    static VlessInboundResponse from(VlessInbound inbound) {
        return new VlessInboundResponse(
                inbound.getId(), inbound.getTag(), inbound.getListenPort(), inbound.getDest(),
                inbound.getServerNames(), inbound.getShortIds(), inbound.getPublicKey(),
                inbound.getFingerprint(), inbound.getFlow(), inbound.isEnabled(),
                inbound.getStatus(), inbound.getLastError(), inbound.getXrayVersion());
    }
}
