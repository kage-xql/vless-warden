package com.example.vlesswarden.vless;

import com.example.vlesswarden.server.Server;
import com.example.vlesswarden.server.ServerNotFoundException;
import com.example.vlesswarden.server.ServerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

@Service
public class VlessClientService {
    private static final Logger log = LoggerFactory.getLogger(VlessClientService.class);

    private final ServerRepository servers;
    private final VlessInboundRepository inbounds;
    private final VlessClientRepository clients;
    private final VlessClientIpRepository clientIps;
    private final VlessConfigService config;
    private final VlessRemoteService remote;
    private final VlessUriBuilder uriBuilder;
    private final VlessOperationLock lock;
    private final VlessIpLimitService ipLimits;

    public VlessClientService(ServerRepository servers, VlessInboundRepository inbounds,
                              VlessClientRepository clients, VlessClientIpRepository clientIps,
                              VlessConfigService config, VlessRemoteService remote,
                              VlessUriBuilder uriBuilder, VlessOperationLock lock,
                              VlessIpLimitService ipLimits) {
        this.servers = servers;
        this.inbounds = inbounds;
        this.clients = clients;
        this.clientIps = clientIps;
        this.config = config;
        this.remote = remote;
        this.uriBuilder = uriBuilder;
        this.lock = lock;
        this.ipLimits = ipLimits;
    }

    public List<VlessClientResponse> list(UUID userId, UUID serverId) {
        VlessInbound inbound = inbound(userId, serverId);
        return config.all(inbound).stream().map(VlessClientResponse::from).toList();
    }

    public VlessClientCreationResponse create(UUID userId, UUID serverId, VlessClientRequest request) {
        // Проверяем владельца до блокировки, чтобы чужой serverId не создавал
        // запись в карте блокировок. Внутри блокировки данные перечитываются.
        server(userId, serverId);
        return lock.withLock(serverId, () -> {
            Server server = server(userId, serverId);
            VlessInbound inbound = inbounds.findByServerIdAndTag(serverId, VlessInbound.DEFAULT_TAG)
                    .orElseThrow(VlessNotFoundException::new);
            return createLocked(server, inbound, request);
        });
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
        VlessClient client = new VlessClient(inbound.getId(), name, email, config.encryptUuid(uuid));
        client.setTrafficLimitBytes(normalizeLimit(request.trafficLimitBytes()));
        client.setIpLimit(normalizeIpLimit(request.ipLimit()));
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
        return withClientLock(userId, clientId, context -> updateLocked(context, request));
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

        // Сначала проверяем ввод, потом меняем удалённый конфиг: иначе
        // невалидное имя оставит сервер и БД в разных состояниях.
        String name = client.getName();
        if (request.name() != null) {
            name = request.name().trim();
            if (name.isEmpty()) throw new IllegalArgumentException("name must not be blank");
        }

        Long limit = client.getTrafficLimitBytes();
        if (Boolean.TRUE.equals(request.clearTrafficLimit())) {
            limit = null;
        } else if (request.trafficLimitBytes() != null) {
            limit = normalizeLimit(request.trafficLimitBytes());
        }

        Instant expiresAt = client.getExpiresAt();
        if (Boolean.TRUE.equals(request.clearExpiresAt())) {
            expiresAt = null;
        } else if (request.expiresAt() != null) {
            expiresAt = request.expiresAt();
        }

        Integer ipLimit = client.getIpLimit();
        if (Boolean.TRUE.equals(request.clearIpLimit())) {
            ipLimit = null;
        } else if (request.ipLimit() != null) {
            ipLimit = normalizeIpLimit(request.ipLimit());
        }
        boolean ipLimitCleared = ipLimit == null && client.getIpLimit() != null;

        applyRemote(server, inbound, client, wasEnabled, enabled, rotate, newUuid);

        if (rotate) {
            clients.updateSettingsAndUuid(client.getId(), name, enabled, expiresAt, limit, ipLimit,
                    config.encryptUuid(newUuid));
        } else {
            clients.updateSettings(client.getId(), name, enabled, expiresAt, limit, ipLimit);
        }

        if (ipLimitCleared) {
            clientIps.deleteByClientId(client.getId());
            clients.updateIpCounts(client.getId(), 0, 0);
            // Снимаем и runtime-правила, иначе забаненный IP останется
            // заблокированным до следующего цикла мониторинга. Ошибка SSH уже
            // не должна отменять сохранённое изменение клиента: следующий
            // цикл мониторинга повторит применение правил.
            try {
                ipLimits.apply(server.getId());
            } catch (RuntimeException e) {
                log.warn("Could not clear Xray IP rules for server {}: {}", server.getId(), e.getMessage());
            }
        }

        VlessClient updated = clients.findById(client.getId()).orElseThrow(VlessNotFoundException::new);
        return new VlessClientCreationResponse(VlessClientResponse.from(updated),
                uriBuilder.uri(server, inbound, updated, newUuid));
    }

    public void delete(UUID userId, UUID clientId) {
        withClientLock(userId, clientId, context -> {
            if (context.client.isEnabled()) {
                requirePinned(context.server);
                List<VlessClient> selected = config.all(context.inbound).stream()
                        .filter(item -> item.isEnabled() && !item.getId().equals(context.client.getId()))
                        .toList();
                remote.removeClient(context.server, context.inbound,
                        config.configFor(context.inbound, selected, Map.of()), context.client.getEmail());
            }
            clients.delete(context.client);
            return null;
        });
    }

    public VlessClientConfigResponse config(UUID userId, UUID clientId) {
        Context context = load(userId, clientId);
        String uuid = config.uuid(context.client);
        return new VlessClientConfigResponse(context.client.getId(), context.client.getName(),
                context.client.isEnabled(), uriBuilder.uri(context.server, context.inbound, context.client, uuid));
    }

    public List<VlessClientIpResponse> ips(UUID userId, UUID clientId) {
        Context context = load(userId, clientId);
        Instant now = Instant.now();
        return clientIps.findAllByClientIdOrderByFirstSeenAsc(context.client.getId()).stream()
                .map(value -> VlessClientIpResponse.from(value, now))
                .toList();
    }

    /** Забывает все IP клиента и снимает его баны, не отключая сам клиент. */
    public VlessClientResponse resetIpLimits(UUID userId, UUID clientId) {
        return withClientLock(userId, clientId, context -> {
            ipLimits.reset(context.server.getId(), context.client.getId());
            VlessClient updated = clients.findById(clientId).orElseThrow(VlessNotFoundException::new);
            return VlessClientResponse.from(updated);
        });
    }

    /**
     * Вызывается мониторингом при превышении лимита или истечении срока.
     * Клиент перечитывается и лимит перепроверяется под блокировкой: между
     * сбором метрик и этим вызовом пользователь мог поднять лимит, продлить
     * срок или удалить клиента. Возвращает true, только если клиент реально
     * отключён и на сервере, и в БД.
     */
    public boolean disableForLimit(UUID clientId) {
        VlessClient snapshot = clients.findById(clientId).orElse(null);
        if (snapshot == null || !snapshot.isEnabled()) return false;
        VlessInbound inbound = inbounds.findById(snapshot.getInboundId())
                .orElseThrow(VlessNotFoundException::new);
        Server server = servers.findById(inbound.getServerId())
                .orElseThrow(VlessNotFoundException::new);
        return lock.withLock(server.getId(), () -> {
            VlessClient current = clients.findById(clientId).orElse(null);
            if (current == null || !current.isEnabled()) return false;
            if (!limitReached(current, Instant.now())) return false;

            requirePinned(server);
            List<VlessClient> selected = config.all(inbound).stream()
                    .filter(item -> item.isEnabled() && !item.getId().equals(current.getId()))
                    .toList();
            remote.removeClient(server, inbound, config.configFor(inbound, selected, Map.of()),
                    current.getEmail());
            // Точечное обновление вместо save(): detached-сущность после
            // удаления строки не должна вставить клиента обратно.
            clients.disable(current.getId());
            log.info("VLESS client {} disabled after reaching its limit", clientId);
            return true;
        });
    }

    private boolean limitReached(VlessClient client, Instant now) {
        boolean expired = client.getExpiresAt() != null && !client.getExpiresAt().isAfter(now);
        boolean overTraffic = client.getTrafficLimitBytes() != null
                && client.getTrafficUsedBytes() >= client.getTrafficLimitBytes();
        return expired || overTraffic;
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

    /**
     * Ключ блокировки — serverId, поэтому сначала нужен только минимальный
     * снимок. Все изменяемые сущности перечитываются уже под блокировкой.
     */
    private <T> T withClientLock(UUID userId, UUID clientId, Function<Context, T> action) {
        VlessClient peek = clients.findById(clientId).orElseThrow(VlessNotFoundException::new);
        VlessInbound inbound = inbounds.findById(peek.getInboundId())
                .orElseThrow(VlessNotFoundException::new);
        return lock.withLock(inbound.getServerId(), () -> action.apply(load(userId, clientId)));
    }

    private void requirePinned(Server server) {
        if (server.getHostKeyFingerprint() == null) {
            throw new IllegalStateException("server host key is not pinned; reconnect and verify the server first");
        }
    }

    private Long normalizeLimit(Long value) {
        return value == null || value == 0 ? null : value;
    }

    private Integer normalizeIpLimit(Integer value) {
        return value == null || value <= 0 ? null : value;
    }

    private String email(String uuid) {
        return "vw_" + uuid.replace("-", "");
    }

    private record Context(Server server, VlessInbound inbound, VlessClient client) {
    }
}
