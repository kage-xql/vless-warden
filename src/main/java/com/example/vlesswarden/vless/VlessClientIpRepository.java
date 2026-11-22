package com.example.vlesswarden.vless;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface VlessClientIpRepository extends JpaRepository<VlessClientIp, UUID> {
    List<VlessClientIp> findAllByClientIdOrderByFirstSeenAsc(UUID clientId);

    List<VlessClientIp> findAllByClientIdIn(Collection<UUID> clientIds);

    @Transactional
    @Modifying
    @Query("delete from VlessClientIp i where i.clientId = :clientId")
    int deleteByClientId(@Param("clientId") UUID clientId);
}
