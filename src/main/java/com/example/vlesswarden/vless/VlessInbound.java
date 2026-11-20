package com.example.vlesswarden.vless;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "vless_inbounds")
public class VlessInbound {
    public static final String DEFAULT_TAG = "vless-warden";
    public static final String DEFAULT_FINGERPRINT = "chrome";
    public static final String DEFAULT_FLOW = "xtls-rprx-vision";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(name = "server_id", nullable = false)
    private UUID serverId;
    @Column(nullable = false, length = 32)
    private String tag;
    @Column(name = "listen_port", nullable = false)
    private int listenPort;
    @Column(nullable = false, length = 255)
    private String dest;
    @Column(name = "server_names", nullable = false, length = 512)
    private String serverNames;
    @Column(name = "short_ids", nullable = false, length = 255)
    private String shortIds;
    @Column(name = "public_key", nullable = false, length = 128)
    private String publicKey;
    @Column(name = "encrypted_private_key", nullable = false)
    private byte[] encryptedPrivateKey;
    @Column(nullable = false, length = 32)
    private String fingerprint = DEFAULT_FINGERPRINT;
    @Column(nullable = false, length = 32)
    private String flow = DEFAULT_FLOW;
    @Column(nullable = false)
    private boolean enabled = true;
    @Column(nullable = false, length = 32)
    private String status = "PENDING";
    @Column(name = "last_error", length = 255)
    private String lastError;
    @Column(name = "xray_version", length = 64)
    private String xrayVersion;
    @Column(name = "firewall_port")
    private Integer firewallPort;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected VlessInbound() {
    }

    public VlessInbound(UUID serverId, String tag, int listenPort, String dest, String serverNames,
                        String shortIds, String publicKey, byte[] encryptedPrivateKey,
                        String fingerprint, String flow) {
        this.serverId = serverId;
        this.tag = tag;
        this.listenPort = listenPort;
        this.dest = dest;
        this.serverNames = serverNames;
        this.shortIds = shortIds;
        this.publicKey = publicKey;
        this.encryptedPrivateKey = encryptedPrivateKey;
        this.fingerprint = fingerprint;
        this.flow = flow;
    }

    @PrePersist
    void created() {
        if (createdAt == null) createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getServerId() { return serverId; }
    public String getTag() { return tag; }
    public int getListenPort() { return listenPort; }
    public String getDest() { return dest; }
    public String getServerNames() { return serverNames; }
    public String getShortIds() { return shortIds; }
    public String getPublicKey() { return publicKey; }
    public byte[] getEncryptedPrivateKey() { return encryptedPrivateKey; }
    public String getFingerprint() { return fingerprint; }
    public String getFlow() { return flow; }
    public boolean isEnabled() { return enabled; }
    public String getStatus() { return status; }
    public String getLastError() { return lastError; }
    public String getXrayVersion() { return xrayVersion; }
    public Integer getFirewallPort() { return firewallPort; }
    public Instant getCreatedAt() { return createdAt; }

    public List<String> serverNameList() {
        return split(serverNames);
    }

    public List<String> shortIdList() {
        return split(shortIds);
    }

    public String primaryServerName() {
        List<String> names = serverNameList();
        return names.isEmpty() ? destHost() : names.get(0);
    }

    public String destHost() {
        int colon = dest.lastIndexOf(':');
        return colon < 0 ? dest : dest.substring(0, colon);
    }

    /**
     * Обновляет параметры inbound, не меняя сгенерированные REALITY-ключи.
     * Статус меняется отдельно после успешного provisioning.
     */
    public void applySettings(int listenPort, String dest, String serverNames, String shortIds,
                              String fingerprint, String flow) {
        this.listenPort = listenPort;
        this.dest = dest;
        this.serverNames = serverNames;
        this.shortIds = shortIds;
        this.fingerprint = fingerprint;
        this.flow = flow;
        this.enabled = true;
    }

    /**
     * Неотсоелённая копия с новыми настройками: позволяет собрать
     * config.json при перепровижении, пока настройки ещё не записаны в БД.
     */
    public VlessInbound withSettings(int listenPort, String dest, String serverNames, String shortIds,
                                     String fingerprint, String flow) {
        VlessInbound copy = new VlessInbound(serverId, tag, listenPort, dest, serverNames, shortIds,
                publicKey, encryptedPrivateKey, fingerprint, flow);
        copy.id = this.id;
        copy.status = this.status;
        copy.lastError = this.lastError;
        copy.xrayVersion = this.xrayVersion;
        copy.firewallPort = this.firewallPort;
        copy.createdAt = this.createdAt;
        copy.enabled = true;
        return copy;
    }

    /** Запоминает порт, для которого панель открыла правило UFW. */
    public void markFirewallAdded(int port) {
        this.firewallPort = port;
    }

    /** Правило UFW, добавленное панелью, больше не актуально. */
    public void clearFirewallPort() {
        this.firewallPort = null;
    }

    public void markReady(String version) {
        this.status = "READY";
        this.lastError = null;
        if (version != null && !version.isBlank()) this.xrayVersion = version;
    }

    public void markError(String error) {
        this.status = "ERROR";
        this.lastError = error == null ? null : (error.length() > 250 ? error.substring(0, 247) + "..." : error);
    }

    public void markStopped() {
        this.status = "STOPPED";
        this.lastError = null;
    }

    private static List<String> split(String value) {
        if (value == null || value.isBlank()) return List.of();
        return Arrays.stream(value.split(",")).map(String::trim).filter(s -> !s.isBlank()).toList();
    }
}
