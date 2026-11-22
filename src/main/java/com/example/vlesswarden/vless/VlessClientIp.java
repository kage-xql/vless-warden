package com.example.vlesswarden.vless;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * IP-адрес, с которого клиент подключался. IPv4 хранится как есть, IPv6
 * нормализуется до /64: privacy-адреса одного устройства не должны выглядеть
 * как несколько разных клиентов.
 *
 * <p>{@code blockedUntil} в будущем означает активный бан. {@code overSince}
 * запоминает начало превышения лимита, чтобы дать короткий grace-период на
 * смену сети.</p>
 */
@Entity
@Table(name = "vless_client_ips")
public class VlessClientIp {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(name = "client_id", nullable = false)
    private UUID clientId;
    @Column(nullable = false, length = 64)
    private String ip;
    @Column(name = "first_seen", nullable = false)
    private Instant firstSeen;
    @Column(name = "last_seen", nullable = false)
    private Instant lastSeen;
    @Column(name = "over_since")
    private Instant overSince;
    @Column(name = "blocked_until")
    private Instant blockedUntil;

    protected VlessClientIp() {
    }

    public VlessClientIp(UUID clientId, String ip, Instant now) {
        this.clientId = clientId;
        this.ip = ip;
        this.firstSeen = now;
        this.lastSeen = now;
    }

    public UUID getId() { return id; }
    public UUID getClientId() { return clientId; }
    public String getIp() { return ip; }
    public Instant getFirstSeen() { return firstSeen; }
    public Instant getLastSeen() { return lastSeen; }
    public Instant getOverSince() { return overSince; }
    public Instant getBlockedUntil() { return blockedUntil; }

    public void apply(Instant firstSeen, Instant lastSeen, Instant overSince, Instant blockedUntil) {
        this.firstSeen = firstSeen;
        this.lastSeen = lastSeen;
        this.overSince = overSince;
        this.blockedUntil = blockedUntil;
    }
}
