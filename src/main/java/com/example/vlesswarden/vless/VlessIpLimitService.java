package com.example.vlesswarden.vless;

import com.example.vlesswarden.server.Server;
import com.example.vlesswarden.server.ServerRepository;
import com.example.vlesswarden.server.ServerStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Считает IP-лимиты и применяет баны к Xray.
 *
 * <p>Оценка лимита выполняется в транзакции мониторинга и только пишет
 * состояние в БД. Применение runtime-правил — отдельный шаг после транзакции:
 * SSH может занимать секунды, и его нельзя держать внутри транзакции.</p>
 */
@Service
public class VlessIpLimitService {
    private static final Logger log = LoggerFactory.getLogger(VlessIpLimitService.class);
    private static final long REPROVISION_TIMEOUT_MILLIS = TimeUnit.MINUTES.toMillis(2);

    private final ServerRepository servers;
    private final VlessInboundRepository inbounds;
    private final VlessClientRepository clients;
    private final VlessClientIpRepository ips;
    private final VlessConfigService config;
    private final VlessRemoteService remote;
    private final VlessOperationLock lock;
    private final ObjectMapper mapper;
    private final Duration grace;
    private final Duration staleAfter;
    private final Duration banTtl;
    private final Duration retryAfter;

    /** Не пытаться перепровижинить один и тот же inbound каждую минуту. */
    private final Map<UUID, Instant> routingRetryAfter = new ConcurrentHashMap<>();

    public VlessIpLimitService(ServerRepository servers, VlessInboundRepository inbounds,
                               VlessClientRepository clients, VlessClientIpRepository ips,
                               VlessConfigService config, VlessRemoteService remote,
                               VlessOperationLock lock, ObjectMapper mapper,
                               @Value("${app.monitoring.ip-limit.grace:2m}") Duration grace,
                               @Value("${app.monitoring.ip-limit.stale-after:30m}") Duration staleAfter,
                               @Value("${app.monitoring.ip-limit.ban-ttl:30m}") Duration banTtl,
                               @Value("${app.monitoring.ip-limit.retry-after:10m}") Duration retryAfter) {
        this.servers = servers;
        this.inbounds = inbounds;
        this.clients = clients;
        this.ips = ips;
        this.config = config;
        this.remote = remote;
        this.lock = lock;
        this.mapper = mapper;
        this.grace = grace;
        this.staleAfter = staleAfter;
        this.banTtl = banTtl;
        this.retryAfter = retryAfter;
    }

    /**
     * Обновляет IP-состояние клиента по списку онлайн-адресов.
     * Вызывается внутри транзакции мониторинга.
     */
    @Transactional
    public void evaluate(VlessClient client, Set<String> onlineIps, Instant now) {
        Integer limit = client.getIpLimit();
        List<VlessClientIp> existing = ips.findAllByClientIdOrderByFirstSeenAsc(client.getId());
        if (limit == null || limit <= 0) {
            if (!existing.isEmpty()) {
                ips.deleteAll(existing);
            }
            if (!existing.isEmpty() || client.getAllowedIpCount() != 0 || client.getBlockedIpCount() != 0) {
                clients.updateIpCounts(client.getId(), 0, 0);
            }
            return;
        }

        Map<String, VlessClientIp> byIp = new HashMap<>();
        List<IpLimitPolicy.Row> rows = new ArrayList<>(existing.size());
        for (VlessClientIp value : existing) {
            byIp.put(value.getIp(), value);
            rows.add(new IpLimitPolicy.Row(value.getIp(), value.getFirstSeen(), value.getLastSeen(),
                    value.getOverSince(), value.getBlockedUntil()));
        }

        IpLimitPolicy.Result result = IpLimitPolicy.evaluate(limit, onlineIps, rows, now,
                new IpLimitPolicy.Settings(grace, staleAfter, banTtl));

        for (String ip : result.deleted()) {
            VlessClientIp value = byIp.get(ip);
            if (value != null) ips.delete(value);
        }
        for (IpLimitPolicy.Row row : result.rows()) {
            VlessClientIp value = byIp.get(row.ip());
            if (value == null) {
                value = new VlessClientIp(client.getId(), row.ip(), now);
            }
            value.apply(row.firstSeen(), row.lastSeen(), row.overSince(), row.blockedUntil());
            ips.save(value);
        }

        clients.updateIpCounts(client.getId(), result.allowedCount(), result.blockedCount());
        if (!result.newlyBlocked().isEmpty()) {
            log.info("VLESS client {} blocked extra IPs: {}", client.getId(), result.newlyBlocked());
        }
    }

    /**
     * Пересобирает runtime-правила для всех inbound сервера. Без банов и без
     * ранее применённых правил ничего не делает.
     *
     * @return клиенты, которые пришлось отключить из-за недоступности routing
     */
    public Set<UUID> apply(UUID serverId) {
        return lock.withLock(serverId, () -> applyLocked(serverId));
    }

    /**
     * Забывает все IP клиента и сразу пересобирает правила сервера.
     * Нужен, если владелец сменил сеть и попал в бан.
     */
    public void reset(UUID serverId, UUID clientId) {
        lock.withLock(serverId, () -> {
            ips.deleteByClientId(clientId);
            clients.updateIpCounts(clientId, 0, 0);
            applyLocked(serverId);
        });
    }

    private Set<UUID> applyLocked(UUID serverId) {
        Server server = servers.findById(serverId).orElse(null);
        if (server == null || server.getHostKeyFingerprint() == null) return Set.of();

        List<VlessInbound> inboundList = inbounds.findAllByServerIdOrderByCreatedAtAsc(serverId);
        if (inboundList.isEmpty()) return Set.of();

        ActiveBans bans = activeBans(inboundList, Instant.now());
        boolean anyRules = inboundList.stream().anyMatch(VlessInbound::isRulesApplied);
        if (bans.isEmpty() && !anyRules) return Set.of();

        if (bans.isEmpty()) {
            try {
                remote.applyIpRules(server, rulesJson(bans));
                markRules(inboundList, false);
            } catch (RuntimeException e) {
                log.warn("Could not clear Xray IP rules on server {}: {}", serverId, e.getMessage());
            }
            return Set.of();
        }

        if (!ensureRoutingReadyLocked(server, inboundList)) {
            return disableClientsLocked(server, inboundList, bans);
        }

        try {
            remote.applyIpRules(server, rulesJson(bans));
            markRules(inboundList, true);
        } catch (RuntimeException e) {
            log.warn("Could not apply Xray IP rules on server {}: {}", serverId, e.getMessage());
            markRules(inboundList, false);
        }
        return Set.of();
    }

    private ActiveBans activeBans(List<VlessInbound> inboundList, Instant now) {
        List<UUID> inboundIds = inboundList.stream().map(VlessInbound::getId).toList();
        List<VlessClient> all = clients.findAllByInboundIdIn(inboundIds);
        if (all.isEmpty()) return new ActiveBans(Map.of(), Map.of());

        Map<UUID, VlessClient> byId = new HashMap<>();
        for (VlessClient client : all) byId.put(client.getId(), client);

        Map<UUID, List<String>> bans = new LinkedHashMap<>();
        for (VlessClientIp value : ips.findAllByClientIdIn(byId.keySet())) {
            if (value.getBlockedUntil() == null || !value.getBlockedUntil().isAfter(now)) continue;
            VlessClient client = byId.get(value.getClientId());
            if (client == null || !client.isEnabled()) continue;
            bans.computeIfAbsent(client.getId(), ignored -> new ArrayList<>()).add(value.getIp());
        }
        for (List<String> values : bans.values()) values.sort(String::compareTo);
        return new ActiveBans(byId, bans);
    }

    /**
     * Добавляет в config.json RoutingService и blackhole на существующих
     * серверах. Это единственный случай, когда панель сама перезапускает Xray.
     */
    private boolean ensureRoutingReadyLocked(Server server, List<VlessInbound> inboundList) {
        boolean ready = true;
        for (VlessInbound inbound : inboundList) {
            if (inbound.isRoutingReady()) continue;
            Instant retry = routingRetryAfter.get(inbound.getId());
            if (retry != null && retry.isAfter(Instant.now())) {
                ready = false;
                continue;
            }
            try {
                String configJson = config.fullConfig(inbound);
                VlessRemoteService.ProvisionResult result = remote.provision(server,
                        inbound.getListenPort(), inbound.getFirewallPort(), configJson,
                        REPROVISION_TIMEOUT_MILLIS);
                inbounds.findById(inbound.getId()).ifPresent(current -> {
                    current.markReady(result.version());
                    if (result.firewallAdded()) current.markFirewallAdded(current.getListenPort());
                    inbounds.save(current);
                });
                routingRetryAfter.remove(inbound.getId());
                servers.updateStatus(server.getId(), ServerStatus.ONLINE, null);
                log.info("Re-provisioned VLESS inbound {} to enable IP limit routing", inbound.getId());
            } catch (RuntimeException e) {
                routingRetryAfter.put(inbound.getId(), Instant.now().plus(retryAfter));
                log.warn("Could not re-provision server {} for IP limits: {}", server.getId(), e.getMessage());
                ready = false;
            }
        }
        return ready;
    }

    private Set<UUID> disableClientsLocked(Server server, List<VlessInbound> inboundList, ActiveBans bans) {
        Set<UUID> disabled = new HashSet<>();
        for (UUID clientId : bans.byClient().keySet()) {
            VlessClient client = bans.clients().get(clientId);
            if (client == null || !client.isEnabled()) continue;
            VlessInbound inbound = inboundList.stream()
                    .filter(item -> item.getId().equals(client.getInboundId()))
                    .findFirst().orElse(null);
            if (inbound == null) continue;
            try {
                List<VlessClient> selected = config.all(inbound).stream()
                        .filter(item -> item.isEnabled() && !item.getId().equals(client.getId()))
                        .toList();
                remote.removeClient(server, inbound, config.configFor(inbound, selected, Map.of()),
                        client.getEmail());
                clients.disable(client.getId());
                ips.deleteByClientId(client.getId());
                clients.updateIpCounts(client.getId(), 0, 0);
                disabled.add(client.getId());
                log.warn("Disabled VLESS client {} because IP limit routing is unavailable", client.getId());
            } catch (RuntimeException e) {
                log.warn("Could not disable VLESS client {} after IP routing failure", client.getId(), e);
            }
        }
        return disabled;
    }

    private void markRules(List<VlessInbound> inboundList, boolean applied) {
        for (VlessInbound inbound : inboundList) {
            inbounds.updateRulesApplied(inbound.getId(), applied);
        }
    }

    private String rulesJson(ActiveBans bans) {
        Map<UUID, String> emails = new HashMap<>();
        bans.clients().forEach((id, client) -> emails.put(id, client.getEmail()));
        return routingRules(bans.byClient(), emails, mapper);
    }

    /** Пустой список банов или список банов — всё, что применяем в Xray. */
    static String routingRules(Map<UUID, List<String>> bansByClient, Map<UUID, String> emails,
                               ObjectMapper mapper) {
        List<Map<String, Object>> rules = new ArrayList<>();
        for (Map.Entry<UUID, List<String>> entry : bansByClient.entrySet()) {
            String email = emails.get(entry.getKey());
            if (email == null || entry.getValue().isEmpty()) continue;
            Map<String, Object> rule = new LinkedHashMap<>();
            rule.put("type", "field");
            rule.put("ruleTag", "vw_ip_" + entry.getKey().toString().replace("-", ""));
            rule.put("user", List.of(email));
            rule.put("source", entry.getValue());
            rule.put("outboundTag", "blocked");
            rules.add(rule);
        }

        Map<String, Object> routing = new LinkedHashMap<>();
        routing.put("rules", rules);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("routing", routing);
        try {
            return mapper.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not build Xray routing rules", e);
        }
    }

    private record ActiveBans(Map<UUID, VlessClient> clients, Map<UUID, List<String>> byClient) {
        boolean isEmpty() {
            return byClient.isEmpty();
        }
    }
}
