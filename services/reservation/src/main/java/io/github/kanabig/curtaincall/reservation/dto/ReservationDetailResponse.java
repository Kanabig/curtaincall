package io.github.kanabig.curtaincall.reservation.dto;

import io.github.kanabig.curtaincall.reservation.entity.ReservationStatus;
import io.github.kanabig.curtaincall.reservation.entity.ReservationView;
import java.time.Instant;
import java.util.List;

/** 예매 상세. 목록 항목에 좌석별 등급·가격과 주문 ID가 붙는다. */
public record ReservationDetailResponse(
        String reservationId,
        String orderId,
        long sessionId,
        Instant sessionStartAt,
        List<SeatLine> seats,
        long amount,
        ReservationStatus status,
        Instant createdAt) {

    public record SeatLine(long seatId, String grade, int price) {}

    public static ReservationDetailResponse from(ReservationView view, List<SeatLine> seats) {
        return new ReservationDetailResponse(
                view.reservationId(),
                view.orderId(),
                view.sessionId(),
                view.sessionStartAt(),
                seats,
                view.amount(),
                view.status(),
                view.createdAt());
    }
}
