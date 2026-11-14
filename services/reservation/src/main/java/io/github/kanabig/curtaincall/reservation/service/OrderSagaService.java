package io.github.kanabig.curtaincall.reservation.service;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.kanabig.curtaincall.common.web.metrics.MetricNames;
import io.github.kanabig.curtaincall.outbox.message.EventEnvelope;
import io.github.kanabig.curtaincall.outbox.message.EventTypes;
import io.github.kanabig.curtaincall.reservation.config.OrderProperties;
import io.github.kanabig.curtaincall.reservation.entity.HeldSeat;
import io.github.kanabig.curtaincall.reservation.entity.Order;
import io.github.kanabig.curtaincall.reservation.entity.OrderStatus;
import io.github.kanabig.curtaincall.reservation.repository.OrderRepository;
import io.github.kanabig.curtaincall.reservation.repository.OrderSeatRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * [RSV-6, RSV-7] 결제 Saga 오케스트레이터. payment의 응답을 받아 주문 상태를 옮기고 다음 명령을 outbox에 남긴다.
 *
 * <p>응답은 중복·순서 뒤바뀜이 올 수 있으므로 주문 행을 잠근 뒤 현재 상태가 기대한 상태일 때만 처리한다. 이미 끝난 주문에 같은 응답이 또 오면 무시한다. 소비는
 * IdempotentConsumer의 트랜잭션 안에서 부른다.
 */
@Service
public class OrderSagaService {

    private static final Logger log = LoggerFactory.getLogger(OrderSagaService.class);
    private static final int RESEND_BATCH = 100;

    private final OrderRepository orders;
    private final OrderSeatRepository seats;
    private final OrderEvents events;
    private final OrderProperties properties;
    private final Clock clock;
    private final Counter stalled;
    private final Counter approvedAfterTerminal;

    public OrderSagaService(
            OrderRepository orders,
            OrderSeatRepository seats,
            OrderEvents events,
            OrderProperties properties,
            Clock clock,
            MeterRegistry meterRegistry) {
        this.orders = orders;
        this.seats = seats;
        this.events = events;
        this.properties = properties;
        this.clock = clock;
        this.stalled = Counter.builder(MetricNames.ORDER_PAYMENT_STALLED)
                .description("결제 응답이 없어 재전송 한도를 넘긴 주문 수")
                .register(meterRegistry);
        this.approvedAfterTerminal = Counter.builder(MetricNames.ORDER_APPROVED_AFTER_TERMINAL)
                .description("실패·만료로 끝난 주문에 승인 응답이 온 횟수")
                .register(meterRegistry);
    }

    @Transactional
    public void handlePaymentEvent(EventEnvelope envelope) {
        String name = EventTypes.nameOf(envelope.eventType());
        JsonNode payload = envelope.payload();
        String orderId = text(payload, "orderId", envelope.aggregateId());
        switch (name) {
            case "PaymentApproved" -> onApproved(orderId, text(payload, "paymentId", null));
            case "PaymentFailed" -> onFailed(orderId, text(payload, "paymentId", null));
            case "PaymentCancelled" -> onCancelled(orderId);
            default -> log.warn("처리하지 않는 결제 이벤트다: eventType={}, eventId={}", envelope.eventType(), envelope.eventId());
        }
    }

    /**
     * 응답이 {@code responseTimeout} 동안 없는 주문에 ApprovePayment를 다시 보내고 점유 연장을 갱신한다. {@code maxResends}번을 넘기면 지표와 로그로 한 번
     * 알리고, 응답이 올 때까지 같은 간격으로 계속 보낸다. payment가 UNKNOWN에서 복구하는 길이 이 재전송이고, 점유 연장이 끊기면 늦은 승인 때 한도 보호가 풀린다.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public int resendDue() {
        Instant now = clock.instant();
        List<Order> due = orders.lockResendDue(now, RESEND_BATCH);
        for (Order order : due) {
            // 한도를 넘는 순간 한 번만 알리고, 응답이 올 때까지 재전송과 점유 연장은 계속한다
            if (order.commandSentCount() - 1 == properties.maxResends()) {
                stalled.increment();
                log.warn("결제 응답이 없어 재전송 한도를 넘었다: orderId={}, sent={}", order.orderId(), order.commandSentCount());
            }
            seats.renewForPayment(order.holdId(), now.plus(properties.paymentHoldExtension()));
            events.approvePayment(order);
            orders.recordResend(order.orderId(), now.plus(properties.responseTimeout()), now);
            log.info("결제 응답이 없어 ApprovePayment를 다시 보낸다: orderId={}", order.orderId());
        }
        return due.size();
    }

    private void onApproved(String orderId, String paymentId) {
        Order order = lockExpecting(orderId, OrderStatus.PAYMENT_REQUESTED, "PaymentApproved");
        if (order == null) {
            return;
        }
        Instant now = clock.instant();
        List<HeldSeat> held = seats.lockByHold(order.holdId());
        boolean allMine = held.size() == order.seatIds().size()
                && held.stream()
                        .allMatch(s -> "HELD".equals(s.status())
                                && Long.valueOf(order.userId()).equals(s.holderId()));
        if (allMine && seats.markSold(order.holdId()) == order.seatIds().size()) {
            transition(order, OrderStatus.CONFIRMED, now);
            orders.completePayment(orderId, paymentId);
            String reservationId = "rsv_" + UUID.randomUUID().toString().replace("-", "");
            orders.insertReservation(reservationId, order, now);
            events.reservationConfirmed(order, reservationId);
            return;
        }
        // 좌석을 잃었다. 돈은 나갔으므로 FAILED로 끝내지 않고 환불까지 간다
        seats.releaseHeld(order.holdId());
        transition(order, OrderStatus.REFUNDING, now);
        orders.completePayment(orderId, paymentId);
        events.cancelPayment(order, paymentId, "SEAT_LOST");
        log.warn("승인됐으나 좌석이 없어 환불한다: orderId={}", orderId);
    }

    private void onFailed(String orderId, String paymentId) {
        Order order = lockExpecting(orderId, OrderStatus.PAYMENT_REQUESTED, "PaymentFailed");
        if (order == null) {
            return;
        }
        seats.releaseHeld(order.holdId());
        transition(order, OrderStatus.FAILED, clock.instant());
        orders.completePayment(orderId, paymentId);
    }

    private void onCancelled(String orderId) {
        // 예매 취소(USER_CANCEL)의 환불 완료는 REFUNDING이 아니라 예매 쪽 상태로 처리한다
        Order order = lockExpecting(orderId, OrderStatus.REFUNDING, "PaymentCancelled");
        if (order == null) {
            return;
        }
        transition(order, OrderStatus.REFUNDED, clock.instant());
        events.orderRefunded(order);
    }

    /** 주문을 잠그고 기대한 상태일 때만 돌려준다. 없는 주문이거나 이미 지나간 상태면 null(무시). */
    private Order lockExpecting(String orderId, OrderStatus expected, String eventName) {
        Order order = orders.lock(orderId).orElse(null);
        if (order == null) {
            log.warn("없는 주문의 결제 이벤트를 무시한다: orderId={}, event={}", orderId, eventName);
            return null;
        }
        if (order.status() != expected) {
            if ("PaymentApproved".equals(eventName)
                    && (order.status() == OrderStatus.FAILED || order.status() == OrderStatus.EXPIRED)) {
                // 결제는 승인됐는데 주문은 실패로 끝난 상태. 설계상 생기면 안 되므로 눈에 띄게 남긴다
                approvedAfterTerminal.increment();
                log.error("종결된 주문에 승인이 왔다: orderId={}, status={}", orderId, order.status());
                return null;
            }
            log.info("이미 지나간 상태라 무시한다: orderId={}, event={}, status={}", orderId, eventName, order.status());
            return null;
        }
        return order;
    }

    private void transition(Order order, OrderStatus to, Instant now) {
        if (!orders.transition(order.orderId(), order.status(), to, now)) {
            throw new IllegalStateException("주문 상태가 바뀌었다: " + order.orderId());
        }
    }

    private static String text(JsonNode payload, String field, String fallback) {
        return payload != null && payload.hasNonNull(field) ? payload.get(field).asText() : fallback;
    }
}
