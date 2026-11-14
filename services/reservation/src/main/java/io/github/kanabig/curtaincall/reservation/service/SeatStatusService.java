package io.github.kanabig.curtaincall.reservation.service;

import io.github.kanabig.curtaincall.reservation.config.SeatStatusProperties;
import io.github.kanabig.curtaincall.reservation.dto.SeatStatusResponse;
import io.github.kanabig.curtaincall.reservation.repository.SeatInventoryRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.stereotype.Service;

/**
 * [RSV-10] 회차 좌석 판매 상태. 회차별 스냅샷을 {@code cacheTtl} 동안 인스턴스 메모리에 둔다. 만료 뒤 첫 요청만 DB를 읽고 나머지는 그 결과를 기다리므로(single-flight)
 * 폴링이 몰려도 회차당 TTL마다 한 번만 읽는다. 만료된 점유는 읽는 시점에 가능으로 본다.
 */
@Service
public class SeatStatusService {

    private static final int SWEEP_THRESHOLD = 1000;

    private final SeatInventoryRepository repository;
    private final SeatInventoryService inventoryService;
    private final SeatStatusProperties properties;
    private final Clock clock;
    private final ConcurrentHashMap<Long, Entry> cache = new ConcurrentHashMap<>();

    public SeatStatusService(
            SeatInventoryRepository repository,
            SeatInventoryService inventoryService,
            SeatStatusProperties properties,
            Clock clock) {
        this.repository = repository;
        this.inventoryService = inventoryService;
        this.properties = properties;
        this.clock = clock;
    }

    public SeatStatusResponse get(long sessionId) {
        long ttlMillis = properties.cacheTtl().toMillis();
        if (ttlMillis <= 0) {
            return load(sessionId);
        }
        Entry entry = cache.computeIfAbsent(sessionId, id -> new Entry());
        Snapshot current = entry.snapshot;
        if (current != null && fresh(current, ttlMillis)) {
            return current.response;
        }
        entry.lock.lock();
        try {
            current = entry.snapshot;
            if (current != null && fresh(current, ttlMillis)) {
                return current.response;
            }
            SeatStatusResponse response = load(sessionId);
            entry.snapshot = new Snapshot(response, clock.millis());
            return response;
        } finally {
            entry.lock.unlock();
            sweepIfLarge(ttlMillis);
        }
    }

    private SeatStatusResponse load(long sessionId) {
        inventoryService.requireReady(sessionId);
        Instant now = clock.instant();
        List<Long> held = new ArrayList<>();
        List<Long> sold = new ArrayList<>();
        repository.forEachUnavailable(sessionId, now, (seatId, status) -> {
            if ("SOLD".equals(status)) {
                sold.add(seatId);
            } else {
                held.add(seatId);
            }
        });
        return new SeatStatusResponse(now.toEpochMilli(), held, sold);
    }

    private boolean fresh(Snapshot snapshot, long ttlMillis) {
        return clock.millis() - snapshot.loadedAtMillis < ttlMillis;
    }

    /** 없는 회차 ID로 항목이 쌓이지 않게, 많아지면 만료된 항목을 지운다. */
    private void sweepIfLarge(long ttlMillis) {
        if (cache.size() > SWEEP_THRESHOLD) {
            cache.entrySet().removeIf(e -> {
                Snapshot s = e.getValue().snapshot;
                return s == null || !fresh(s, ttlMillis);
            });
        }
    }

    private static final class Entry {
        final ReentrantLock lock = new ReentrantLock();
        volatile Snapshot snapshot;
    }

    private record Snapshot(SeatStatusResponse response, long loadedAtMillis) {}
}
