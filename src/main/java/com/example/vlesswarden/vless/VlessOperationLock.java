package com.example.vlesswarden.vless;

import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Сериализует изменения удалённого конфига для одного сервера.
 *
 * <p>Без этого два параллельных запроса могут собрать разные config.json из
 * снимков БД, а затем применить их в обратном порядке — и один клиент пропадёт
 * из файла после перезапуска Xray. Панель рассчитана на один экземпляр, поэтому
 * локальной блокировки достаточно.</p>
 */
@Component
public class VlessOperationLock {
    private final ConcurrentHashMap<UUID, ReentrantLock> locks = new ConcurrentHashMap<>();

    public <T> T withLock(UUID serverId, Supplier<T> action) {
        ReentrantLock lock = locks.computeIfAbsent(serverId, ignored -> new ReentrantLock());
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }

    public void withLock(UUID serverId, Runnable action) {
        withLock(serverId, () -> {
            action.run();
            return null;
        });
    }
}
