package com.example.vlesswarden.monitoring;

import com.example.vlesswarden.vless.VlessClientMetricResponse;

import java.util.List;
import java.util.UUID;

public record MonitorUpdate(UUID serverId, String status, String connectionError,
                            ServerMetricResponse server, List<ClientUpdate> clients) {
    public record ClientUpdate(UUID clientId, boolean enabled, VlessClientMetricResponse metrics) {
    }
}
