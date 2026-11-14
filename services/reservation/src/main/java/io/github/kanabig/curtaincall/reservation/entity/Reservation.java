package io.github.kanabig.curtaincall.reservation.entity;

import java.util.List;

/** 확정된 예매. 주문 하나당 하나다. */
public record Reservation(
        String reservationId,
        String orderId,
        long userId,
        long sessionId,
        List<Long> seatIds,
        long amount,
        ReservationStatus status) {}
