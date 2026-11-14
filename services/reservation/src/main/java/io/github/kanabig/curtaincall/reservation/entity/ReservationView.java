package io.github.kanabig.curtaincall.reservation.entity;

import java.time.Instant;
import java.util.List;

/** 조회용 예매. 회차 시작 시각은 아직 받지 못했으면 null이다. */
public record ReservationView(
        String reservationId,
        String orderId,
        long sessionId,
        Instant sessionStartAt,
        List<Long> seatIds,
        long amount,
        ReservationStatus status,
        Instant createdAt) {}
