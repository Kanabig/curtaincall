package io.github.kanabig.curtaincall.reservation.dto;

import io.github.kanabig.curtaincall.reservation.entity.Order;
import io.github.kanabig.curtaincall.reservation.entity.OrderStatus;
import java.time.Instant;

/** 주문 생성 응답. 같은 Idempotency-Key로 다시 요청하면 저장해 둔 이 응답을 그대로 돌려준다. */
public record OrderResponse(String orderId, long amount, OrderStatus status, Instant expiresAt) {

    public static OrderResponse from(Order order) {
        return new OrderResponse(order.orderId(), order.amount(), order.status(), order.holdExpiresAt());
    }
}
