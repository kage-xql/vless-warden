package com.example.vlesswarden.vless;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface VlessClientMetricRepository extends JpaRepository<VlessClientMetric, UUID> {
    List<VlessClientMetric> findAllByClientIdAndTimestampBetweenOrderByTimestampAsc(UUID clientId, Instant from, Instant to);

    Optional<VlessClientMetric> findFirstByClientIdOrderByTimestampDesc(UUID clientId);

    long deleteByTimestampBefore(Instant before);
}
