package com.example.vlesswarden.vless;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "vless_clients")
public class VlessClient {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(name = "inbound_id", nullable = false)
    private UUID inboundId;
    @Column(nullable = false, length = 100)
    private String name;
    @Column(nullable = false, length = 120)
    private String email;
    @Column(name = "encrypted_uuid", nullable = false)
    private byte[] encryptedUuid;
    @Column(nullable = false)
    private boolean enabled = true;
    @Column(name = "expires_at")
    private Instant expiresAt;
    @Column(name = "traffic_limit_bytes")
    private Long trafficLimitBytes;
    @Column(name = "traffic_used_bytes", nullable = false)
    private long trafficUsedBytes;
    @Column(name = "ip_limit")
    private Integer ipLimit;
    @Column(name = "allowed_ip_count", nullable = false)
    private int allowedIpCount;
    @Column(name = "blocked_ip_count", nullable = false)
    private int blockedIpCount;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected VlessClient() {
    }

    public VlessClient(UUID inboundId, String name, String email, byte[] encryptedUuid) {
        this.inboundId = inboundId;
        this.name = name;
        this.email = email;
        this.encryptedUuid = encryptedUuid;
    }

    @PrePersist
    void created() {
        if (createdAt == null) createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getInboundId() { return inboundId; }
    public String getName() { return name; }
    public String getEmail() { return email; }
    public byte[] getEncryptedUuid() { return encryptedUuid; }
    public boolean isEnabled() { return enabled; }
    public Instant getExpiresAt() { return expiresAt; }
    public Long getTrafficLimitBytes() { return trafficLimitBytes; }
    public long getTrafficUsedBytes() { return trafficUsedBytes; }
    public Integer getIpLimit() { return ipLimit; }
    public int getAllowedIpCount() { return allowedIpCount; }
    public int getBlockedIpCount() { return blockedIpCount; }
    public Instant getCreatedAt() { return createdAt; }

    public void setName(String name) { this.name = name; }
    public void setEncryptedUuid(byte[] value) { this.encryptedUuid = value; }
    public void setEnabled(boolean value) { this.enabled = value; }
    public void setExpiresAt(Instant value) { this.expiresAt = value; }
    public void setTrafficLimitBytes(Long value) { this.trafficLimitBytes = value; }
    public void setIpLimit(Integer value) { this.ipLimit = value; }

    public void addTrafficUsedBytes(long delta) {
        if (delta > 0) this.trafficUsedBytes += delta;
    }

    public void resetTrafficUsedBytes() { this.trafficUsedBytes = 0; }
}
