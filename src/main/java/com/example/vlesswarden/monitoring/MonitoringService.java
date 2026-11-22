package com.example.vlesswarden.monitoring;

import com.example.vlesswarden.server.Server;
import com.example.vlesswarden.server.ServerCredentialService;
import com.example.vlesswarden.server.ServerRepository;
import com.example.vlesswarden.server.ServerStatus;
import com.example.vlesswarden.ssh.SshClientService;
import com.example.vlesswarden.vless.IpAddresses;
import com.example.vlesswarden.vless.VlessClient;
import com.example.vlesswarden.vless.VlessClientMetric;
import com.example.vlesswarden.vless.VlessClientMetricRepository;
import com.example.vlesswarden.vless.VlessClientMetricResponse;
import com.example.vlesswarden.vless.VlessClientRepository;
import com.example.vlesswarden.vless.VlessClientService;
import com.example.vlesswarden.vless.VlessInbound;
import com.example.vlesswarden.vless.VlessInboundRepository;
import com.example.vlesswarden.vless.VlessIpLimitService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class MonitoringService {
    private static final Logger log = LoggerFactory.getLogger(MonitoringService.class);

    /**
     * Системные метрики плюс счётчики Xray. Счётчики Xray обнуляются при
     * перезапуске процесса, поэтому панель считает дельты и переживает рестарт.
     */
    private static final String COLLECT_SCRIPT = """
            set -euo pipefail
            cores=$(getconf _NPROCESSORS_ONLN)
            cpu=$(awk -v n="$cores" '{value=($1/n)*100; if(value>100)value=100; printf "%.2f",value}' /proc/loadavg)
            ram=$(awk '/MemTotal:/ {total=$2} /MemAvailable:/ {available=$2} END {printf "%.2f", (total-available)*100/total}' /proc/meminfo)
            disk=$(df -P / | awk 'NR==2 {gsub(/%/,"",$5); print $5}')
            read -r net_rx net_tx < <(awk -F: 'NR>2 {gsub(/ /,"",$1); if($1!="lo" && $1 !~ /^(docker|veth|br-|virbr)/){rx+=$2; tx+=$10}} END {printf "%s %s\\n",rx+0,tx+0}' /proc/net/dev)
            printf 'SYS\\t%s\\t%s\\t%s\\t%s\\t%s\\n' "$cpu" "$ram" "$disk" "$net_rx" "$net_tx"

            XRAY=/opt/vless-warden/bin/xray
            if [ -x "$XRAY" ]; then
              { "$XRAY" api statsquery --server=127.0.0.1:10085 -pattern 'user>>>' 2>/dev/null || true; } \\
                | awk '
                    /"name":/ {
                      line=$0
                      sub(/^[[:space:]]*"name":[[:space:]]*"/, "", line)
                      sub(/",?[[:space:]]*$/, "", line)
                      name=line
                    }
                    /"value":/ {
                      line=$0
                      sub(/^[[:space:]]*"value":[[:space:]]*/, "", line)
                      gsub(/"/, "", line)
                      sub(/[,}[:space:]]*$/, "", line)
                      print "USR\\t" name "\\t" line
                    }' || true
              { "$XRAY" api statsgetallonlineusers --server=127.0.0.1:10085 2>/dev/null || true; } \\
                | grep -oE 'vw_[a-f0-9]{32}' | sed 's/^/ONL\\t/' || true
              # statsonlineiplist -all отдаёт JSON; кодируем в base64, чтобы
              # многострочный вывод не смешивался с TSV-строками метрик.
              ipl=$("$XRAY" api statsonlineiplist --server=127.0.0.1:10085 -all 2>/dev/null || true)
              if [ -n "$ipl" ]; then
                printf 'IPL\\t%s\\n' "$(printf '%s' "$ipl" | base64 | tr -d '\\n')"
              fi
            fi
            """;

    private final ServerRepository servers;
    private final VlessInboundRepository inbounds;
    private final VlessClientRepository clients;
    private final ServerMetricRepository serverMetrics;
    private final VlessClientMetricRepository clientMetrics;
    private final SshClientService ssh;
    private final ServerCredentialService credentials;
    private final VlessClientService clientService;
    private final VlessIpLimitService ipLimits;
    private final MonitoringEventHub events;
    private final TransactionTemplate transactions;
    private final Duration retention;
    private final ObjectMapper mapper;

    public MonitoringService(ServerRepository servers, VlessInboundRepository inbounds,
                             VlessClientRepository clients, ServerMetricRepository serverMetrics,
                             VlessClientMetricRepository clientMetrics, SshClientService ssh,
                             ServerCredentialService credentials, VlessClientService clientService,
                             VlessIpLimitService ipLimits,
                             MonitoringEventHub events,
                             PlatformTransactionManager txManager,
                             ObjectMapper mapper,
                             @Value("${app.monitoring.retention:30d}") Duration retention) {
        this.servers = servers;
        this.inbounds = inbounds;
        this.clients = clients;
        this.serverMetrics = serverMetrics;
        this.clientMetrics = clientMetrics;
        this.ssh = ssh;
        this.credentials = credentials;
        this.clientService = clientService;
        this.ipLimits = ipLimits;
        this.events = events;
        this.transactions = new TransactionTemplate(txManager);
        this.mapper = mapper;
        this.retention = retention;
    }

    @Scheduled(cron = "0 20 3 * * *")
    public void purgeOldMetrics() {
        Instant cutoff = Instant.now().minus(retention);
        transactions.execute(status -> {
            serverMetrics.deleteByTimestampBefore(cutoff);
            clientMetrics.deleteByTimestampBefore(cutoff);
            return null;
        });
    }

    @Scheduled(fixedDelayString = "${app.monitoring.interval:60s}", initialDelayString = "${app.monitoring.initial-delay:5s}")
    public void collect() {
        for (Server server : servers.findAll()) {
            UUID id = server.getId();
            try {
                CollectionResult result = collectOne(id);
                if (result == null) continue;

                // SSH и другие удалённые вызовы выполняются уже вне транзакции:
                // они могут секунды, а удержание строки и VlessOperationLock
                // блокирует параллельное редактирование клиентов/сервера.
                Set<UUID> disabled = new HashSet<>();
                for (UUID clientId : result.pendingDisables()) {
                    try {
                        if (clientService.disableForLimit(clientId)) {
                            disabled.add(clientId);
                        }
                    } catch (RuntimeException e) {
                        log.warn("Could not disable limited VLESS client {}", clientId, e);
                    }
                }
                // IP-баны применяются вне транзакции: SSH-вызов может
                // занимать время, а блокировка на сервере мешала бы созданию/редактированию.
                try {
                    disabled.addAll(ipLimits.apply(id));
                } catch (RuntimeException e) {
                    log.warn("Could not apply IP limit rules for server {}", id, e);
                }
                List<MonitorUpdate.ClientUpdate> clients = result.update().clients().stream()
                        .map(client -> disabled.contains(client.clientId())
                                ? new MonitorUpdate.ClientUpdate(client.clientId(), false, client.metrics())
                                : client)
                        .toList();
                events.publish(server.getUserId(), new MonitorUpdate(result.update().serverId(),
                        result.update().status(), result.update().connectionError(),
                        result.update().server(), clients));
            } catch (RuntimeException e) {
                markError(server, id, e);
            }
        }
    }

    /** SSH-сбор и парсинг вне транзакции, затем одна транзакция сохранения. */
    private CollectionResult collectOne(UUID serverId) {
        Server server = servers.findById(serverId).orElse(null);
        if (server == null || server.getHostKeyFingerprint() == null) return null;

        String output = ssh.runSudoScript(server.getHost(), server.getSshPort(), server.getSshUser(),
                credentials.decrypt(server), server.getHostKeyFingerprint(), COLLECT_SCRIPT);

        return transactions.execute(status -> persistMetrics(serverId, output));
    }

    private CollectionResult persistMetrics(UUID serverId, String output) {
        Instant now = Instant.now();
        ServerMetric serverMetric = null;
        Map<String, long[]> traffic = new HashMap<>();
        Set<String> online = new HashSet<>();
        Map<String, Set<String>> onlineIpByEmail = new HashMap<>();

        for (String line : output.lines().toList()) {
            String[] fields = line.split("\\t");
            if (fields.length == 6 && fields[0].equals("SYS")) {
                serverMetric = new ServerMetric(serverId, now, metric(fields[1]), metric(fields[2]),
                        metric(fields[3]), counter(fields[4]), counter(fields[5]));
            } else if (fields.length == 3 && fields[0].equals("USR")) {
                addTraffic(traffic, fields[1], counter(fields[2]));
            } else if (fields.length == 2 && fields[0].equals("ONL")) {
                online.add(fields[1]);
            } else if (fields.length == 2 && fields[0].equals("IPL")) {
                onlineIpByEmail.putAll(parseOnlineIps(fields[1]));
            }
        }
        if (serverMetric == null) throw new IllegalStateException("remote metrics response is incomplete");
        serverMetrics.save(serverMetric);

        List<MonitorUpdate.ClientUpdate> updates = new ArrayList<>();
        List<UUID> pendingDisables = new ArrayList<>();
        for (VlessInbound inbound : inbounds.findAllByServerIdOrderByCreatedAtAsc(serverId)) {
            for (VlessClient client : clients.findAllByInboundIdOrderByCreatedAtDesc(inbound.getId())) {
                ipLimits.evaluate(client, onlineIpByEmail.getOrDefault(client.getEmail(), Set.of()), now);

                long[] current = traffic.getOrDefault(client.getEmail(), new long[]{0L, 0L});
                Optional<VlessClientMetric> previous =
                        clientMetrics.findFirstByClientIdOrderByTimestampDesc(client.getId());
                long previousRx = previous.map(VlessClientMetric::getRxBytes).orElse(0L);
                long previousTx = previous.map(VlessClientMetric::getTxBytes).orElse(0L);
                // Если счётчик Xray стал меньше предыдущего, процесс перезапускался
                // и текущее значение уже является накопленным заново.
                long deltaRx = current[0] >= previousRx ? current[0] - previousRx : current[0];
                long deltaTx = current[1] >= previousTx ? current[1] - previousTx : current[1];
                long delta = deltaRx + deltaTx;
                if (delta > 0) {
                    clients.addTraffic(client.getId(), delta);
                }
                long used = client.getTrafficUsedBytes() + Math.max(delta, 0);

                VlessClientMetric metric = clientMetrics.save(new VlessClientMetric(
                        client.getId(), now, current[0], current[1], online.contains(client.getEmail())));

                boolean expired = client.getExpiresAt() != null && !client.getExpiresAt().isAfter(now);
                boolean overTraffic = client.getTrafficLimitBytes() != null
                        && used >= client.getTrafficLimitBytes();
                if ((expired || overTraffic) && client.isEnabled()) {
                    pendingDisables.add(client.getId());
                }
                updates.add(new MonitorUpdate.ClientUpdate(client.getId(), client.isEnabled(),
                        VlessClientMetricResponse.from(metric)));
            }
        }

        // Точечное обновление статуса: save() на сервере затёр бы изменения
        // host/credentials, сделанные пользователем за время SSH-сбора.
        servers.updateStatus(serverId, ServerStatus.ONLINE, null);
        MonitorUpdate update = new MonitorUpdate(serverId, ServerStatus.ONLINE.name(),
                null, ServerMetricResponse.from(serverMetric), updates);
        return new CollectionResult(pendingDisables, update);
    }

    private void markError(Server server, UUID id, RuntimeException e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        String reason = root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
        log.warn("Monitoring failed for server {}: {}", id, reason, e);
        String message = monitoringError(reason);
        try {
            transactions.execute(status -> {
                servers.updateStatus(id, ServerStatus.ERROR, message);
                return null;
            });
        } catch (RuntimeException markError) {
            log.warn("Could not persist monitoring error for {}", id, markError);
        }
        events.publish(server.getUserId(),
                new MonitorUpdate(id, ServerStatus.ERROR.name(), message, null, List.of()));
    }

    private static String monitoringError(String reason) {
        String message = "Monitoring failed: " + reason;
        return message.length() > 250 ? message.substring(0, 247) + "..." : message;
    }

    private record CollectionResult(List<UUID> pendingDisables, MonitorUpdate update) {
    }

    private void addTraffic(Map<String, long[]> traffic, String name, long value) {
        String prefix = "user>>>";
        String marker = ">>>traffic>>>";
        if (!name.startsWith(prefix)) return;
        int index = name.indexOf(marker);
        if (index < 0) return;
        String email = name.substring(prefix.length(), index);
        String direction = name.substring(index + marker.length());
        long[] counters = traffic.computeIfAbsent(email, ignored -> new long[2]);
        if (direction.equals("uplink")) counters[0] = value;
        else if (direction.equals("downlink")) counters[1] = value;
    }

    private Map<String, Set<String>> parseOnlineIps(String base64Json) {
        if (base64Json == null || base64Json.isBlank()) return Map.of();
        try {
            byte[] bytes = Base64.getDecoder().decode(base64Json);
            JsonNode root = mapper.readTree(bytes);
            Map<String, Set<String>> result = new HashMap<>();
            for (JsonNode user : root.path("users")) {
                String email = user.path("email").asText("");
                if (email.isEmpty()) continue;
                Set<String> ips = new HashSet<>();
                for (JsonNode entry : user.path("ips")) {
                    String ip = entry.path("ip").asText("");
                    if (ip.isEmpty()) continue;
                    try {
                        ips.add(IpAddresses.normalize(ip));
                    } catch (RuntimeException e) {
                        log.debug("Ignoring unparseable online IP {}", ip);
                    }
                }
                if (!ips.isEmpty()) result.put(email, ips);
            }
            return result;
        } catch (Exception e) {
            log.warn("Could not parse Xray online IP list: {}", e.getMessage());
            return Map.of();
        }
    }

    private double metric(String value) {
        double number = Double.parseDouble(value);
        if (!Double.isFinite(number) || number < 0 || number > 100) {
            throw new IllegalArgumentException("Invalid percentage metric");
        }
        return number;
    }

    private long counter(String value) {
        long number = Long.parseLong(value.startsWith("\"") ? value.replace("\"", "") : value);
        if (number < 0) throw new IllegalArgumentException("Invalid counter metric");
        return number;
    }
}
