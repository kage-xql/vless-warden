package com.example.vlesswarden.vless;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface VlessClientRepository extends JpaRepository<VlessClient, UUID> {
    List<VlessClient> findAllByInboundIdOrderByCreatedAtDesc(UUID inboundId);

    Optional<VlessClient> findFirstByInboundIdAndEmail(UUID inboundId, String email);
}
