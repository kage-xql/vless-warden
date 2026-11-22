package com.example.vlesswarden.vless;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface VlessClientRepository extends JpaRepository<VlessClient, UUID> {
    List<VlessClient> findAllByInboundIdOrderByCreatedAtDesc(UUID inboundId);

    List<VlessClient> findAllByInboundIdIn(Collection<UUID> inboundIds);

    Optional<VlessClient> findFirstByInboundIdAndEmail(UUID inboundId, String email);

    /**
     * Обновляет только редактируемые пользователем поля. Так счётчик трафика,
     * который параллельно наращивает мониторинг, не затирается устаревшим
     * значением из загруженной сущности.
     */
    @Transactional
    @Modifying
    @Query("""
            update VlessClient c
               set c.name = :name,
                   c.enabled = :enabled,
                   c.expiresAt = :expiresAt,
                   c.trafficLimitBytes = :limit,
                   c.ipLimit = :ipLimit
             where c.id = :id
            """)
    int updateSettings(@Param("id") UUID id, @Param("name") String name, @Param("enabled") boolean enabled,
                       @Param("expiresAt") Instant expiresAt, @Param("limit") Long limit,
                       @Param("ipLimit") Integer ipLimit);

    @Transactional
    @Modifying
    @Query("update VlessClient c set c.encryptedUuid = :uuid where c.id = :id")
    int updateUuid(@Param("id") UUID id, @Param("uuid") byte[] uuid);

    /**
     * Один запрос на все изменения, включая UUID: при ротации нельзя оставить
     * в БД старый UUID, если Xray уже переключился на новый.
     */
    @Transactional
    @Modifying
    @Query("""
            update VlessClient c
               set c.name = :name,
                   c.enabled = :enabled,
                   c.expiresAt = :expiresAt,
                   c.trafficLimitBytes = :limit,
                   c.ipLimit = :ipLimit,
                   c.encryptedUuid = :uuid
             where c.id = :id
            """)
    int updateSettingsAndUuid(@Param("id") UUID id, @Param("name") String name,
                              @Param("enabled") boolean enabled, @Param("expiresAt") Instant expiresAt,
                              @Param("limit") Long limit, @Param("ipLimit") Integer ipLimit,
                              @Param("uuid") byte[] uuid);

    /** Атомарный инкремент: не перезаписывает остальные поля клиента. */
    @Transactional
    @Modifying
    @Query("update VlessClient c set c.trafficUsedBytes = c.trafficUsedBytes + :delta where c.id = :id")
    int addTraffic(@Param("id") UUID id, @Param("delta") long delta);

    /**
     * Отключение без save(): save() на detached-сущности после удаления строки
     * вставил бы клиента обратно.
     */
    @Transactional
    @Modifying
    @Query("update VlessClient c set c.enabled = false where c.id = :id")
    int disable(@Param("id") UUID id);

    @Transactional
    @Modifying
    @Query("""
            update VlessClient c
               set c.allowedIpCount = :allowed,
                   c.blockedIpCount = :blocked
             where c.id = :id
            """)
    int updateIpCounts(@Param("id") UUID id, @Param("allowed") int allowed, @Param("blocked") int blocked);
}
