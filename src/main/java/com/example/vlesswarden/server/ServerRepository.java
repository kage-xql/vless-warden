package com.example.vlesswarden.server;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ServerRepository extends JpaRepository<Server, UUID> {
    List<Server> findAllByUserIdOrderByCreatedAtDesc(UUID userId);

    Optional<Server> findByIdAndUserId(UUID id, UUID userId);

    /**
     * Обновляет только статус. Мониторинг не должен сохранять (merge) весь
     * Server: за время SSH-сбора пользователь мог изменить host или credentials,
     * и такой save() затёр бы его правки.
     */
    @Transactional
    @Modifying
    @Query("update Server s set s.status = :status, s.connectionError = :error where s.id = :id")
    int updateStatus(@Param("id") UUID id, @Param("status") ServerStatus status, @Param("error") String error);
}
