package com.example.vlesswarden.vless;

import java.time.Instant;
import java.util.UUID;

public record VlessClientMetricResponse(
        UUID clientId,
        Instant timestamp,
        long rxBytes,
        long txBytes,
        boolean online
) {
    public static VlessClientMetricResponse from(VlessClientMetric metric) {
        return new VlessClientMetricResponse(
                metric.getClientId(), metric.getTimestamp(),
                metric.getRxBytes(), metric.getTxBytes(), metric.isOnline());
    }
}
