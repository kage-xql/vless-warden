package com.example.vlesswarden.vless;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface VlessInboundRepository extends JpaRepository<VlessInbound, UUID> {
    Optional<VlessInbound> findByServerIdAndTag(UUID serverId, String tag);

    List<VlessInbound> findAllByServerIdOrderByCreatedAtAsc(UUID serverId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from VlessInbound i where i.id = :id and i.serverId = :serverId")
    Optional<VlessInbound> lockByIdAndServerId(@Param("id") UUID id, @Param("serverId") UUID serverId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from VlessInbound i where i.serverId = :serverId and i.tag = :tag")
    Optional<VlessInbound> lockByServerIdAndTag(@Param("serverId") UUID serverId, @Param("tag") String tag);
}
