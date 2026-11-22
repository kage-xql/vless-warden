package com.example.vlesswarden.vless;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Чистая логика IP-лимита: какие адреса разрешены, какие забанены и какие
 * записи пора удалить. Не трогает БД и SSH, поэтому легко тестируется.
 *
 * <p>Правила:</p>
 * <ul>
 *   <li>лимит считается по одновременно онлайн IP, а не по всем известным;</li>
 *   <li>активно забаненные IP не занимают слот;</li>
 *   <li>если онлайн IP меньше лимита, баны снимаются, чтобы владелец мог
 *       вернуться с новым адресом;</li>
 *   <li>лишний IP получает grace-период и банится, только если превышение
 *       сохраняется;</li>
 *   <li>старые неактивные записи удаляются, чтобы адреса не копились.</li>
 * </ul>
 */
public final class IpLimitPolicy {
    private IpLimitPolicy() {
    }

    public record Settings(Duration grace, Duration staleAfter, Duration banTtl) {
    }

    public record Row(String ip, Instant firstSeen, Instant lastSeen, Instant overSince, Instant blockedUntil) {
        public boolean blockedAt(Instant now) {
            return blockedUntil != null && blockedUntil.isAfter(now);
        }

        Row touched(Instant now) {
            return new Row(ip, firstSeen, now, overSince, blockedUntil);
        }

        Row withOverSince(Instant value) {
            return new Row(ip, firstSeen, lastSeen, value, blockedUntil);
        }

        Row withBlockedUntil(Instant value) {
            return new Row(ip, firstSeen, lastSeen, overSince, value);
        }
    }

    public record Result(List<Row> rows, Set<String> deleted, int allowedCount, int blockedCount,
                         Set<String> newlyBlocked) {
    }

    public static Result evaluate(int limit, Set<String> online, Collection<Row> existing,
                                  Instant now, Settings settings) {
        if (limit <= 0) throw new IllegalArgumentException("IP limit must be positive");

        Map<String, Row> rows = new LinkedHashMap<>();
        for (Row row : existing) rows.put(row.ip(), row);

        for (String ip : online) {
            Row row = rows.get(ip);
            rows.put(ip, row == null ? new Row(ip, now, now, null, null) : row.touched(now));
        }

        Set<String> deleted = new HashSet<>();

        // Истёкший бан: онлайн-адрес снова участвует в распределении слотов,
        // офлайн-запись больше не нужна.
        for (String ip : new ArrayList<>(rows.keySet())) {
            Row row = rows.get(ip);
            if (row.blockedUntil() == null || row.blockedUntil().isAfter(now)) continue;
            if (online.contains(ip)) {
                rows.put(ip, row.withBlockedUntil(null).withOverSince(null));
            } else {
                rows.remove(ip);
                deleted.add(ip);
            }
        }

        Instant staleCutoff = now.minus(settings.staleAfter());
        for (String ip : new ArrayList<>(rows.keySet())) {
            Row row = rows.get(ip);
            if (online.contains(ip) || row.blockedAt(now)) continue;
            if (row.lastSeen().isBefore(staleCutoff)) {
                rows.remove(ip);
                deleted.add(ip);
            }
        }

        List<Row> blockedOnline = rows.values().stream()
                .filter(row -> online.contains(row.ip()) && row.blockedAt(now))
                .sorted(Comparator.comparing(Row::lastSeen).reversed())
                .toList();

        List<Row> candidates = new ArrayList<>(rows.values().stream()
                .filter(row -> online.contains(row.ip()) && !row.blockedAt(now))
                .toList());

        // Свободные слоты заполняем самыми свежими забаненными адресами:
        // если владелец сменил сеть, его новый IP не должен ждать истечения бана.
        int spare = limit - candidates.size();
        if (spare > 0) {
            for (Row row : blockedOnline) {
                if (spare == 0) break;
                Row unblocked = row.withBlockedUntil(null).withOverSince(null);
                rows.put(row.ip(), unblocked);
                candidates.add(unblocked);
                spare--;
            }
        }

        candidates.sort(Comparator.comparing(Row::firstSeen).thenComparing(Row::ip));

        Set<String> newlyBlocked = new HashSet<>();
        for (int i = 0; i < candidates.size(); i++) {
            Row row = candidates.get(i);
            if (i < limit) {
                if (row.overSince() != null) rows.put(row.ip(), row.withOverSince(null));
                continue;
            }
            if (row.overSince() == null) {
                rows.put(row.ip(), row.withOverSince(now));
            } else if (!now.isBefore(row.overSince().plus(settings.grace()))) {
                rows.put(row.ip(), new Row(row.ip(), row.firstSeen(), row.lastSeen(),
                        null, now.plus(settings.banTtl())));
                newlyBlocked.add(row.ip());
            }
        }

        int allowed = 0;
        int blocked = 0;
        for (Row row : rows.values()) {
            if (row.blockedAt(now)) blocked++;
            else if (online.contains(row.ip())) allowed++;
        }
        return new Result(new ArrayList<>(rows.values()), deleted, allowed, blocked, newlyBlocked);
    }
}
