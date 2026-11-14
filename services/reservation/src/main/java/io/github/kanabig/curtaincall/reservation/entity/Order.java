package io.github.kanabig.curtaincall.reservation.entity;

import java.time.Instant;
import java.util.List;

/** 주문. {@code holdExpiresAt}은 주문을 만들 때 점유 좌석 중 가장 이른 만료 시각이다. */
public record Order(
        String orderId,
        String holdId,
        long userId,
        long sessionId,
        List<Long> seatIds,
        long amount,
        OrderStatus status,
        Instant holdExpiresAt,
        String cardToken,
        String paymentId,
        int commandSentCount) {}
