package com.example.vlesswarden.vless;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IpLimitPolicyTest {
    private final Instant now = Instant.parse("2026-11-22T10:00:00Z");
    private final Instant seen = Instant.parse("2026-11-22T09:00:00Z");
    private final IpLimitPolicy.Settings settings = new IpLimitPolicy.Settings(
            Duration.ofMinutes(2), Duration.ofMinutes(30), Duration.ofMinutes(30));

    private List<IpLimitPolicy.Row> rows(String... ips) {
        List<IpLimitPolicy.Row> result = new ArrayList<>();
        for (String ip : ips) {
            result.add(new IpLimitPolicy.Row(ip, seen, seen, null, null));
        }
        return result;
    }

    private IpLimitPolicy.Result evaluate(int limit, Set<String> online, List<IpLimitPolicy.Row> existing) {
        return IpLimitPolicy.evaluate(limit, online, existing, now, settings);
    }

    private Map<String, IpLimitPolicy.Row> asMap(IpLimitPolicy.Result result) {
        Map<String, IpLimitPolicy.Row> map = new HashMap<>();
        for (IpLimitPolicy.Row row : result.rows()) map.put(row.ip(), row);
        return map;
    }

    @Test
    void allowsAsManyOnlineIpsAsLimit() {
        IpLimitPolicy.Result result = evaluate(2, Set.of("a", "b", "c"), rows("a", "b", "c"));
        Map<String, IpLimitPolicy.Row> map = asMap(result);
        // a и b — старые по firstSeen, c — лишний: даём grace, не баним сразу.
        assertEquals(3, result.allowedCount());
        assertEquals(0, result.blockedCount());
        assertEquals(3, map.size());
        assertEquals(now, map.get("c").overSince());
    }

    @Test
    void bansExtraIpAfterGrace() {
        List<IpLimitPolicy.Row> existing = List.of(
                new IpLimitPolicy.Row("a", seen.minus(Duration.ofHours(1)), seen.minus(Duration.ofHours(1)), null, null),
                new IpLimitPolicy.Row("b", seen, seen, now.minus(Duration.ofMinutes(5)), null));
        IpLimitPolicy.Result result = evaluate(1, Set.of("a", "b"), existing);
        Map<String, IpLimitPolicy.Row> map = asMap(result);
        assertEquals(1, result.allowedCount());
        assertEquals(1, result.blockedCount());
        assertEquals(Set.of("b"), result.newlyBlocked());
        assertTrue(map.get("b").blockedUntil().isAfter(now));
    }

    @Test
    void unblocksToFillFreeSlotWhenOwnerMoves() {
        List<IpLimitPolicy.Row> existing = List.of(
                new IpLimitPolicy.Row("a", seen, seen, null, now.plus(Duration.ofMinutes(30))));
        IpLimitPolicy.Result result = evaluate(1, Set.of("a"), existing);
        Map<String, IpLimitPolicy.Row> map = asMap(result);
        assertEquals(1, result.allowedCount());
        assertEquals(0, result.blockedCount());
        assertNull(map.get("a").blockedUntil());
    }

    @Test
    void deletesExpiredBanForOfflineIp() {
        List<IpLimitPolicy.Row> existing = List.of(
                new IpLimitPolicy.Row("a", seen, seen, null, now.minus(Duration.ofMinutes(1))));
        IpLimitPolicy.Result result = evaluate(2, Set.of("b"), existing);
        assertEquals(Set.of("a"), result.deleted());
        assertFalse(asMap(result).containsKey("a"));
    }

    @Test
    void deletesStaleInactiveRow() {
        Instant old = now.minus(Duration.ofHours(1));
        List<IpLimitPolicy.Row> existing = List.of(new IpLimitPolicy.Row("a", old, old, null, null));
        IpLimitPolicy.Result result = evaluate(2, Set.of("b"), existing);
        assertEquals(Set.of("a"), result.deleted());
    }

    @Test
    void keepsRecentInactiveRow() {
        Instant recent = now.minus(Duration.ofMinutes(5));
        List<IpLimitPolicy.Row> existing = List.of(new IpLimitPolicy.Row("a", recent, recent, null, null));
        IpLimitPolicy.Result result = evaluate(2, Set.of("b"), existing);
        assertTrue(result.deleted().isEmpty());
        assertTrue(asMap(result).containsKey("a"));
    }

    @Test
    void keepsBlockedRowUntilTtl() {
        List<IpLimitPolicy.Row> existing = List.of(
                new IpLimitPolicy.Row("a", seen, seen, null, now.plus(Duration.ofMinutes(30))));
        IpLimitPolicy.Result result = evaluate(2, Set.of(), existing);
        assertEquals(1, result.blockedCount());
        assertTrue(asMap(result).get("a").blockedUntil().isAfter(now));
    }

    @Test
    void newIpGetsFirstSeenNow() {
        IpLimitPolicy.Result result = evaluate(2, Set.of("a", "b"), List.of());
        Map<String, IpLimitPolicy.Row> map = asMap(result);
        assertEquals(now, map.get("a").firstSeen());
        assertEquals(now, map.get("a").lastSeen());
    }

    @Test
    void rejectsNonPositiveLimit() {
        assertThrows(IllegalArgumentException.class,
                () -> IpLimitPolicy.evaluate(0, Set.of(), List.of(), now, settings));
    }

    @Test
    void clearsOverSinceForAllowedIp() {
        List<IpLimitPolicy.Row> existing = List.of(
                new IpLimitPolicy.Row("a", seen.minus(Duration.ofHours(1)), seen, now.minus(Duration.ofHours(2)), null));
        IpLimitPolicy.Result result = evaluate(2, Set.of("a"), existing);
        assertEquals(0, result.blockedCount());
        assertNull(asMap(result).get("a").overSince());
    }

    @Test
    void ignoresUndecidableRowsInDeleted() {
        List<IpLimitPolicy.Row> existing = List.of(
                new IpLimitPolicy.Row("a", seen, seen, null, null));
        IpLimitPolicy.Result result = evaluate(1, Set.of("a"), existing);
        assertNotNull(asMap(result).get("a"));
    }
}
