package com.example.vlesswarden.vless;

import com.example.vlesswarden.server.Server;
import com.example.vlesswarden.server.ServerNotFoundException;
import com.example.vlesswarden.server.ServerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class VlessClientService {
    private static final Logger log = LoggerFactory.getLogger(VlessClientService.class);

    private final ServerRepository servers;
    private final VlessInboundRepository inbounds;
    private final VlessClientRepository clients;
    private final VlessConfigService config;
    private final VlessRemoteService remote;
    private final VlessUriBuilder uriBuilder;
    private final VlessOperationLock lock;

    public VlessClientService(ServerRepository servers, VlessInboundRepository inbounds,
                              VlessClientRepository clients, VlessConfigService config,
                              VlessRemoteService remote, VlessUriBuilder uriBuilder,
                              VlessOperationLock lock) {
        this.servers = servers;
        this.inbounds = inbounds;
        this.clients = clients;
        this.config = config;
        this.remote = remote;
        this.uriBuilder = uriBuilder;
        this.lock = lock;
    }

    public List<VlessClientResponse> list(UUID userId, UUID serverId) {
        VlessInbound inbound = inbound(userId, serverId);
        return config.all(inbound).stream().map(VlessClientResponse::from).toList();
    }

    public VlessClientCreationResponse create(UUID userId, UUID serverId, VlessClientRequest request) {
        Server server = server(userId, serverId);
        VlessInbound inbound = inbounds.findByServerIdAndTag(serverId, VlessInbound.DEFAULT_TAG)
                .orElseThrow(VlessNotFoundException::new);
        return lock.withLock(serverId, () -> createLocked(server, inbound, request));
    }

    private VlessClientCreationResponse createLocked(Server server, VlessInbound inbound, VlessClientRequest request) {
        if (!"READY".equals(inbound.getStatus())) {
            throw new IllegalStateException("VLESS inbound is not ready; provision or fix it first");
        }
        requirePinned(server);

        String name = request.name().trim();
        if (name.isEmpty()) throw new IllegalArgumentException("name must not be blank");

        String uuid = UUID.randomUUID().toString();
        String email = email(uuid);
        VlessClient client = clients.save(new VlessClient(inbound.getId(), name, email, config.encryptUuid(uuid)));
        client.setTrafficLimitBytes(normalizeLimit(request.trafficLimitBytes()));
        client.setExpiresAt(request.expiresAt());
        client = clients.save(client);

        try {
            String full = config.fullConfig(inbound);
            String userConfig = config.clientConfig(inbound, client, uuid);
            remote.addClient(server, inbound, full, userConfig, email);
        } catch (RuntimeException e) {
            clients.delete(client);
            log.warn("Could not add VLESS client to server {}: {}", server.getId(), e.getMessage());
            throw e;
        }
        return new VlessClientCreationResponse(VlessClientResponse.from(client),
                uriBuilder.uri(server, inbound, client, uuid));
    }

    public VlessClientCreationResponse update(UUID userId, UUID clientId, VlessClientUpdateRequest request) {
        Context context = load(userId, clientId);
        return lock.withLock(context.server.getId(), () -> updateLocked(context, request));
    }

    private VlessClientCreationResponse updateLocked(Context context, VlessClientUpdateRequest request) {
        Server server = context.server;
        VlessInbound inbound = context.inbound;
        VlessClient client = context.client;

        boolean wasEnabled = client.isEnabled();
        boolean enabled = request.enabled() == null ? wasEnabled : request.enabled();
        boolean rotate = Boolean.TRUE.equals(request.rotateUuid());
        String oldUuid = config.uuid(client);
        String newUuid = rotate ? UUID.randomUUID().toString() : oldUuid;

        applyRemote(server, inbound, client, wasEnabled, enabled, rotate, newUuid);

        if (request.name() != null) {
            String name = request.name().trim();
            if (name.isEmpty()) throw new IllegalArgumentException("name must not be blank");
            client.setName(name);
        }
        if (Boolean.TRUE.equals(request.clearTrafficLimit())) {
            client.setTrafficLimitBytes(null);
        } else if (request.trafficLimitBytes() != null) {
            client.setTrafficLimitBytes(normalizeLimit(request.trafficLimitBytes()));
        }
        if (Boolean.TRUE.equals(request.clearExpiresAt())) {
            client.setExpiresAt(null);
        } else if (request.expiresAt() != null) {
            client.setExpiresAt(request.expiresAt());
        }
        client.setEnabled(enabled);
        if (rotate) client.setEncryptedUuid(config.encryptUuid(newUuid));
        client = clients.save(client);

        return new VlessClientCreationResponse(VlessClientResponse.from(client),
                uriBuilder.uri(server, inbound, client, newUuid));
    }

    public void delete(UUID userId, UUID clientId) {
        Context context = load(userId, clientId);
        lock.withLock(context.server.getId(), () -> {
            if (context.client.isEnabled()) {
                requirePinned(context.server);
                List<VlessClient> selected = config.all(context.inbound).stream()
                        .filter(item -> item.isEnabled() && !item.getId().equals(context.client.getId()))
                        .toList();
                remote.removeClient(context.server, context.inbound,
                        config.configFor(context.inbound, selected, Map.of()), context.client.getEmail());
            }
            clients.delete(context.client);
        });
    }

    public VlessClientConfigResponse config(UUID userId, UUID clientId) {
        Context context = load(userId, clientId);
        String uuid = config.uuid(context.client);
        return new VlessClientConfigResponse(context.client.getId(), context.client.getName(),
                context.client.isEnabled(), uriBuilder.uri(context.server, context.inbound, context.client, uuid));
    }

    /**
     * Вызывается мониторингом при превышении лимита или истечении срока.
     * Отключение обязательно подтверждается на самом сервере.
     */
    public void disableForLimit(UUID clientId) {
        VlessClient client = clients.findById(clientId).orElseThrow(VlessNotFoundException::new);
        if (!client.isEnabled()) return;
        VlessInbound inbound = inbounds.findById(client.getInboundId()).orElseThrow(VlessNotFoundException::new);
        Server server = servers.findById(inbound.getServerId()).orElseThrow(VlessNotFoundException::new);
        lock.withLock(server.getId(), () -> {
            requirePinned(server);
            List<VlessClient> selected = config.all(inbound).stream()
                    .filter(item -> item.isEnabled() && !item.getId().equals(client.getId()))
                    .toList();
            remote.removeClient(server, inbound, config.configFor(inbound, selected, Map.of()), client.getEmail());
            client.setEnabled(false);
            clients.save(client);
            log.info("VLESS client {} disabled after reaching its limit", clientId);
        });
    }

    private void applyRemote(Server server, VlessInbound inbound, VlessClient client,
                             boolean wasEnabled, boolean enabled, boolean rotate, String newUuid) {
        if (wasEnabled == enabled && !(enabled && rotate)) return;
        requirePinned(server);

        List<VlessClient> all = config.all(inbound);
        if (!enabled) {
            List<VlessClient> selected = all.stream()
                    .filter(item -> item.isEnabled() && !item.getId().equals(client.getId()))
                    .toList();
            remote.removeClient(server, inbound, config.configFor(inbound, selected, Map.of()), client.getEmail());
            return;
        }

        List<VlessClient> selected = all.stream()
                .filter(item -> item.isEnabled() || item.getId().equals(client.getId()))
                .toList();
        Map<UUID, String> overrides = new HashMap<>();
        overrides.put(client.getId(), newUuid);
        String full = config.configFor(inbound, selected, overrides);
        String userConfig = config.clientConfig(inbound, client, newUuid);
        if (wasEnabled) {
            remote.replaceClient(server, inbound, full, userConfig, client.getEmail());
        } else {
            remote.addClient(server, inbound, full, userConfig, client.getEmail());
        }
    }

    private VlessInbound inbound(UUID userId, UUID serverId) {
        server(userId, serverId);
        return inbounds.findByServerIdAndTag(serverId, VlessInbound.DEFAULT_TAG)
                .orElseThrow(VlessNotFoundException::new);
    }

    private Server server(UUID userId, UUID serverId) {
        return servers.findByIdAndUserId(serverId, userId).orElseThrow(ServerNotFoundException::new);
    }

    private Context load(UUID userId, UUID clientId) {
        VlessClient client = clients.findById(clientId).orElseThrow(VlessNotFoundException::new);
        VlessInbound inbound = inbounds.findById(client.getInboundId()).orElseThrow(VlessNotFoundException::new);
        Server server = servers.findByIdAndUserId(inbound.getServerId(), userId)
                .orElseThrow(VlessNotFoundException::new);
        return new Context(server, inbound, client);
    }

    private void requirePinned(Server server) {
        if (server.getHostKeyFingerprint() == null) {
            throw new IllegalStateException("server host key is not pinned; reconnect and verify the server first");
        }
    }

    private Long normalizeLimit(Long value) {
        return value == null || value == 0 ? null : value;
    }

    private String email(String uuid) {
        return "vw_" + uuid.replace("-", "");
    }

    private record Context(Server server, VlessInbound inbound, VlessClient client) {
    }
}
