package com.example.vlesswarden.monitoring;

import com.example.vlesswarden.server.ServerNotFoundException;
import com.example.vlesswarden.server.ServerRepository;
import com.example.vlesswarden.vless.VlessClient;
import com.example.vlesswarden.vless.VlessClientMetricRepository;
import com.example.vlesswarden.vless.VlessClientMetricResponse;
import com.example.vlesswarden.vless.VlessClientRepository;
import com.example.vlesswarden.vless.VlessInbound;
import com.example.vlesswarden.vless.VlessInboundRepository;
import com.example.vlesswarden.vless.VlessNotFoundException;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class MonitoringController {
    private final ServerRepository servers;
    private final VlessInboundRepository inbounds;
    private final VlessClientRepository clients;
    private final ServerMetricRepository serverMetrics;
    private final VlessClientMetricRepository clientMetrics;
    private final MonitoringEventHub events;

    public MonitoringController(ServerRepository servers, VlessInboundRepository inbounds,
                                VlessClientRepository clients, ServerMetricRepository serverMetrics,
                                VlessClientMetricRepository clientMetrics, MonitoringEventHub events) {
        this.servers = servers;
        this.inbounds = inbounds;
        this.clients = clients;
        this.serverMetrics = serverMetrics;
        this.clientMetrics = clientMetrics;
        this.events = events;
    }

    @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(Authentication authentication) {
        return events.subscribe(userId(authentication));
    }

    @GetMapping("/servers/{serverId}/metrics")
    public List<ServerMetricResponse> serverHistory(Authentication authentication, @PathVariable UUID serverId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        requireServerOwner(authentication, serverId);
        Instant[] range = range(from, to);
        return serverMetrics.findAllByServerIdAndTimestampBetweenOrderByTimestampAsc(serverId, range[0], range[1])
                .stream().map(ServerMetricResponse::from).toList();
    }

    @GetMapping("/vless/clients/{clientId}/metrics")
    public List<VlessClientMetricResponse> clientHistory(Authentication authentication, @PathVariable UUID clientId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        requireClientOwner(authentication, clientId);
        Instant[] range = range(from, to);
        return clientMetrics.findAllByClientIdAndTimestampBetweenOrderByTimestampAsc(clientId, range[0], range[1])
                .stream().map(VlessClientMetricResponse::from).toList();
    }

    private Instant[] range(Instant from, Instant to) {
        Instant end = to == null ? Instant.now() : to;
        Instant start = from == null ? end.minusSeconds(86_400) : from;
        if (start.isAfter(end)) throw new IllegalArgumentException("from must be before to");
        if (start.isBefore(end.minusSeconds(7 * 86_400L))) {
            throw new IllegalArgumentException("metric history is limited to seven days");
        }
        return new Instant[]{start, end};
    }

    private void requireServerOwner(Authentication authentication, UUID serverId) {
        if (servers.findByIdAndUserId(serverId, userId(authentication)).isEmpty()) {
            throw new ServerNotFoundException();
        }
    }

    private void requireClientOwner(Authentication authentication, UUID clientId) {
        VlessClient client = clients.findById(clientId).orElseThrow(VlessNotFoundException::new);
        VlessInbound inbound = inbounds.findById(client.getInboundId()).orElseThrow(VlessNotFoundException::new);
        if (servers.findByIdAndUserId(inbound.getServerId(), userId(authentication)).isEmpty()) {
            throw new VlessNotFoundException();
        }
    }

    private UUID userId(Authentication authentication) {
        return (UUID) authentication.getPrincipal();
    }
}
