package io.github.kanabig.curtaincall.reservation.dto;

import io.github.kanabig.curtaincall.reservation.entity.Order;
import io.github.kanabig.curtaincall.reservation.entity.OrderStatus;
import java.util.List;

/** 결제 화면이 폴링하는 주문 상태. paymentStatus는 결제 전·만료면 null,
 * failureCode는 FAILED·EXPIRED로 끝난 주문에만 값이 있다. */
public record OrderDetailResponse(
        String orderId,
        long sessionId,
        List<Long> seatIds,
        long amount,
        OrderStatus status,
        String paymentStatus,
        String failureCode) {

    public static OrderDetailResponse from(Order order) {
        return new OrderDetailResponse(
                order.orderId(),
                order.sessionId(),
                order.seatIds(),
                order.amount(),
                order.status(),
                paymentStatus(order.status()),
                order.failureCode());
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
