package io.github.kanabig.curtaincall.reservation.dto;

import io.github.kanabig.curtaincall.reservation.entity.ReservationStatus;
import io.github.kanabig.curtaincall.reservation.entity.ReservationView;
import java.time.Instant;
import java.util.List;

/** 예매 목록의 한 건. sessionStartAt은 회차 시각을 아직 받지 못했으면 null이다. */
public record ReservationResponse(
        String reservationId,
        long sessionId,
        Instant sessionStartAt,
        List<Long> seatIds,
        long amount,
        ReservationStatus status,
        Instant createdAt) {

    public static ReservationResponse from(ReservationView view) {
        return new ReservationResponse(
                view.reservationId(),
                view.sessionId(),
                view.sessionStartAt(),
                view.seatIds(),
                view.amount(),
                view.status(),
                view.createdAt());
    }
}
