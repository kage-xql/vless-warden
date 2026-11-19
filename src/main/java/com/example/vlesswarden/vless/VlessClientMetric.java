package com.example.vlesswarden.vless;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "vless_client_metrics")
public class VlessClientMetric {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(name = "client_id", nullable = false)
    private UUID clientId;
    @Column(name = "ts", nullable = false)
    private Instant timestamp;
    @Column(name = "rx_bytes", nullable = false)
    private long rxBytes;
    @Column(name = "tx_bytes", nullable = false)
    private long txBytes;
    @Column(nullable = false)
    private boolean online;

    protected VlessClientMetric() {
    }

    public VlessClientMetric(UUID clientId, Instant timestamp, long rxBytes, long txBytes, boolean online) {
        this.clientId = clientId;
        this.timestamp = timestamp;
        this.rxBytes = rxBytes;
        this.txBytes = txBytes;
        this.online = online;
    }

    public UUID getId() { return id; }
    public UUID getClientId() { return clientId; }
    public Instant getTimestamp() { return timestamp; }
    public long getRxBytes() { return rxBytes; }
    public long getTxBytes() { return txBytes; }
    public boolean isOnline() { return online; }
}
