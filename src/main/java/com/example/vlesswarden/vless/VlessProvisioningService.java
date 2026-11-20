package com.example.vlesswarden.vless;

import com.example.vlesswarden.crypto.CryptoService;
import com.example.vlesswarden.server.Server;
import com.example.vlesswarden.server.ServerNotFoundException;
import com.example.vlesswarden.server.ServerRepository;
import com.example.vlesswarden.server.ServerStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class VlessProvisioningService {
    private static final Logger log = LoggerFactory.getLogger(VlessProvisioningService.class);

    private static final int DEFAULT_PORT = 443;
    private static final int DEFAULT_DEST_PORT = 443;
    private static final String DEFAULT_DEST_HOST = "www.microsoft.com";
    private static final int API_PORT = 10085;

    private static final Pattern HOST = Pattern.compile("^[A-Za-z0-9](?:[A-Za-z0-9.-]{0,253}[A-Za-z0-9])?$");
    private static final Pattern SHORT_ID = Pattern.compile("^[0-9a-fA-F]{0,16}$");
    private static final List<String> FINGERPRINTS = List.of(
            "chrome", "firefox", "safari", "ios", "android", "edge", "random", "randomized");

    private final ServerRepository servers;
    private final VlessInboundRepository inbounds;
    private final VlessConfigService config;
    private final CryptoService crypto;
    private final VlessRemoteService remote;
    private final VlessOperationLock lock;

    public VlessProvisioningService(ServerRepository servers, VlessInboundRepository inbounds,
                                    VlessConfigService config, CryptoService crypto,
                                    VlessRemoteService remote, VlessOperationLock lock) {
        this.servers = servers;
        this.inbounds = inbounds;
        this.config = config;
        this.crypto = crypto;
        this.remote = remote;
        this.lock = lock;
    }

    /**
     * Создаёт inbound или переприменяет настройки существующего.
     * REALITY-ключи существующего inbound сохраняются, чтобы не ломать
     * уже выданные клиентам vless:// ссылки.
     */
    public VlessInboundResponse provision(UUID userId, UUID serverId, VlessProvisionRequest request) {
        VlessProvisionRequest req = request == null
                ? new VlessProvisionRequest(null, null, null, null, null, null)
                : request;

        int port = req.listenPort() == null ? DEFAULT_PORT : req.listenPort();
        String dest = validateDest(req.dest());
        List<String> serverNames = validateServerNames(req.serverNames(), destHost(dest));
        List<String> shortIds = validateShortIds(req.shortIds());
        String fingerprint = validateFingerprint(req.fingerprint());
        String flow = validateFlow(req.flow());

        // Всё перечитывается и проверяется под блокировкой.
        return lock.withLock(serverId, () -> {
            Server server = servers.findByIdAndUserId(serverId, userId)
                    .orElseThrow(ServerNotFoundException::new);
            requirePinned(server);
            validatePort(port, server.getSshPort());
            return provisionLocked(server, port, dest, serverNames, shortIds, fingerprint, flow);
        });
    }

    private VlessInboundResponse provisionLocked(Server server, int port, String dest, List<String> serverNames,
                                                  List<String> shortIds, String fingerprint, String flow) {
        UUID serverId = server.getId();
        VlessInbound existing = inbounds.findByServerIdAndTag(serverId, VlessInbound.DEFAULT_TAG).orElse(null);
        String joinedNames = String.join(",", serverNames);
        String joinedShortIds = String.join(",", shortIds);

        // Настройки копируем в detached-объект, а не меняем сущность БД: она
        // сохранится только после успешного remote-provisioning, а при ошибке
        // перечитается и помечается ERROR в исходном состоянии.
        VlessKeys.KeyPair keys = existing == null ? VlessKeys.generateRealityKeyPair() : null;
        VlessInbound candidate = existing != null
                ? existing.withSettings(port, dest, joinedNames, joinedShortIds, fingerprint, flow)
                : new VlessInbound(serverId, VlessInbound.DEFAULT_TAG, port, dest, joinedNames, joinedShortIds,
                        keys.publicKey(), crypto.encrypt(keys.privateKey().getBytes(StandardCharsets.UTF_8)),
                        fingerprint, flow);

        Integer oldFirewallPort = existing == null ? null : existing.getFirewallPort();
        try {
            String configJson = config.fullConfig(candidate);
            VlessRemoteService.ProvisionResult result = remote.provision(server, port, oldFirewallPort, configJson);

            VlessInbound saved;
            if (existing == null) {
                candidate.markReady(result.version());
                if (result.firewallAdded()) candidate.markFirewallAdded(port);
                else if (oldFirewallPort != null && oldFirewallPort != port) candidate.clearFirewallPort();
                saved = inbounds.save(candidate);
            } else {
                VlessInbound current = inbounds.findByServerIdAndTag(serverId, VlessInbound.DEFAULT_TAG)
                        .orElseThrow(VlessNotFoundException::new);
                current.applySettings(port, dest, joinedNames, joinedShortIds, fingerprint, flow);
                current.markReady(result.version());
                if (result.firewallAdded()) {
                    current.markFirewallAdded(port);
                } else if (oldFirewallPort != null && oldFirewallPort != port) {
                    // Скрипт удалил правило старого порта, новое уже существовало.
                    current.clearFirewallPort();
                }
                saved = inbounds.save(current);
            }
            servers.updateStatus(serverId, ServerStatus.ONLINE, null);
            return VlessInboundResponse.from(saved);
        } catch (RuntimeException e) {
            if (existing != null) {
                inbounds.findByServerIdAndTag(serverId, VlessInbound.DEFAULT_TAG).ifPresent(current -> {
                    current.markError(errorMessage(e));
                    inbounds.save(current);
                });
            }
            log.warn("VLESS provisioning failed for server {}: {}", serverId, errorMessage(e));
            throw e;
        }
    }

    @Transactional(readOnly = true)
    public VlessInboundResponse get(UUID userId, UUID serverId) {
        servers.findByIdAndUserId(serverId, userId).orElseThrow(ServerNotFoundException::new);
        return inbounds.findByServerIdAndTag(serverId, VlessInbound.DEFAULT_TAG)
                .map(VlessInboundResponse::from)
                .orElseThrow(VlessNotFoundException::new);
    }

    private void requirePinned(Server server) {
        if (server.getHostKeyFingerprint() == null) {
            throw new IllegalStateException("server host key is not pinned; reconnect and verify the server first");
        }
    }

    private void validatePort(int port, int sshPort) {
        if (port < 1 || port > 65535) throw new IllegalArgumentException("listen port must be between 1 and 65535");
        if (port == sshPort) throw new IllegalArgumentException("listen port must differ from the SSH port");
        if (port == API_PORT) throw new IllegalArgumentException("port 10085 is reserved for the Xray API");
    }

    private String validateDest(String raw) {
        String value = raw == null || raw.isBlank() ? DEFAULT_DEST_HOST + ":" + DEFAULT_DEST_PORT : raw.trim();
        int colon = value.lastIndexOf(':');
        if (colon <= 0 || colon == value.length() - 1) {
            value = value + ":" + DEFAULT_DEST_PORT;
            colon = value.lastIndexOf(':');
        }
        String host = value.substring(0, colon);
        String portText = value.substring(colon + 1);
        if (!HOST.matcher(host).matches()) throw new IllegalArgumentException("reality dest must be a valid host");
        int port;
        try {
            port = Integer.parseInt(portText);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("reality dest port must be a number");
        }
        if (port < 1 || port > 65535) throw new IllegalArgumentException("reality dest port must be between 1 and 65535");
        return host + ":" + port;
    }

    private List<String> validateServerNames(List<String> raw, String fallback) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        if (raw != null) {
            for (String item : raw) {
                if (item == null) continue;
                String name = item.trim().toLowerCase(Locale.ROOT);
                if (name.isEmpty()) continue;
                if (!HOST.matcher(name).matches()) {
                    throw new IllegalArgumentException("invalid REALITY server name: " + item);
                }
                values.add(name);
            }
        }
        if (values.isEmpty()) values.add(fallback);
        return new ArrayList<>(values);
    }

    private List<String> validateShortIds(List<String> raw) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        if (raw != null) {
            for (String item : raw) {
                if (item == null) continue;
                String id = item.trim();
                if (id.isEmpty()) continue;
                if (!SHORT_ID.matcher(id).matches() || id.length() % 2 != 0) {
                    throw new IllegalArgumentException("REALITY shortId must be an even-length hex string up to 16 characters");
                }
                values.add(id);
            }
        }
        if (values.isEmpty()) values.add(VlessKeys.shortId());
        return new ArrayList<>(values);
    }

    private String validateFingerprint(String raw) {
        String value = raw == null || raw.isBlank() ? VlessInbound.DEFAULT_FINGERPRINT : raw.trim().toLowerCase(Locale.ROOT);
        if (!FINGERPRINTS.contains(value)) throw new IllegalArgumentException("unsupported fingerprint");
        return value;
    }

    private String validateFlow(String raw) {
        if (raw == null) return VlessInbound.DEFAULT_FLOW;
        String value = raw.trim();
        if (value.isEmpty()) return "";
        if (!value.equals(VlessInbound.DEFAULT_FLOW)) throw new IllegalArgumentException("unsupported VLESS flow");
        return value;
    }

    private String destHost(String dest) {
        return dest.substring(0, dest.lastIndexOf(':'));
    }

    private String errorMessage(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        String message = root.getMessage();
        return message == null || message.isBlank() ? root.getClass().getSimpleName() : message;
    }
}
