package io.github.kanabig.curtaincall.reservation.dto;

import io.github.kanabig.curtaincall.reservation.entity.Order;
import io.github.kanabig.curtaincall.reservation.entity.OrderStatus;
import java.util.List;

/** 결제 화면이 폴링하는 주문 상태. paymentStatus는 결제 전·만료면 null이다. */
public record OrderDetailResponse(
        String orderId, long sessionId, List<Long> seatIds, long amount, OrderStatus status, String paymentStatus) {

    public static OrderDetailResponse from(Order order) {
        return new OrderDetailResponse(
                order.orderId(),
                order.sessionId(),
                order.seatIds(),
                order.amount(),
                order.status(),
                paymentStatus(order.status()));
    }

    private static String paymentStatus(OrderStatus status) {
        return switch (status) {
            case PENDING_PAYMENT, EXPIRED -> null;
            case PAYMENT_REQUESTED, REFUNDING -> "PROCESSING";
            case CONFIRMED -> "APPROVED";
            case FAILED -> "FAILED";
            case REFUNDED -> "REFUNDED";
        };
    }
}
