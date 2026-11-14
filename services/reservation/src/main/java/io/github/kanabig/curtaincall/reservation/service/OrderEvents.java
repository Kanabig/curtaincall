package io.github.kanabig.curtaincall.reservation.service;

import io.github.kanabig.curtaincall.outbox.relay.OutboxWriter;
import io.github.kanabig.curtaincall.reservation.config.OrderTopicsConfig;
import io.github.kanabig.curtaincall.reservation.entity.Order;
import io.github.kanabig.curtaincall.reservation.entity.Reservation;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/** 주문 상태 변경과 같은 트랜잭션에서 명령·이벤트를 outbox에 기록한다. 키는 모두 orderId라 한 주문의 메시지는 한 파티션에 순서대로 들어간다. */
@Component
class OrderEvents {

    private final OutboxWriter outbox;

    OrderEvents(OutboxWriter outbox) {
        this.outbox = outbox;
    }

    void approvePayment(Order order) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderId", order.orderId());
        payload.put("userId", String.valueOf(order.userId()));
        payload.put("amount", order.amount());
        payload.put("cardToken", order.cardToken());
        outbox.append(OrderTopicsConfig.PAYMENT_COMMAND_TOPIC, order.orderId(), "ApprovePayment", payload);
    }

    void cancelPayment(Order order, String paymentId, String reason) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderId", order.orderId());
        payload.put("paymentId", paymentId);
        payload.put("amount", order.amount());
        payload.put("reason", reason);
        outbox.append(OrderTopicsConfig.PAYMENT_COMMAND_TOPIC, order.orderId(), "CancelPayment", payload);
    }

    void reservationConfirmed(Order order, String reservationId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reservationId", reservationId);
        payload.put("orderId", order.orderId());
        payload.put("userId", order.userId());
        payload.put("sessionId", order.sessionId());
        payload.put("seatIds", order.seatIds());
        payload.put("amount", order.amount());
        outbox.append(OrderTopicsConfig.ORDER_TOPIC, order.orderId(), "ReservationConfirmed", payload);
    }

    void reservationCancelled(Order order, Reservation reservation) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reservationId", reservation.reservationId());
        payload.put("orderId", order.orderId());
        payload.put("userId", reservation.userId());
        payload.put("sessionId", reservation.sessionId());
        payload.put("seatIds", reservation.seatIds());
        payload.put("amount", reservation.amount());
        outbox.append(OrderTopicsConfig.ORDER_TOPIC, order.orderId(), "ReservationCancelled", payload);
    }

    void orderRefunded(Order order) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderId", order.orderId());
        payload.put("userId", order.userId());
        payload.put("sessionId", order.sessionId());
        payload.put("amount", order.amount());
        outbox.append(OrderTopicsConfig.ORDER_TOPIC, order.orderId(), "OrderRefunded", payload);
    }
}
