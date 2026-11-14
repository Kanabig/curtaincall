package io.github.kanabig.curtaincall.reservation.service;

import io.github.kanabig.curtaincall.common.web.error.BusinessException;
import io.github.kanabig.curtaincall.reservation.config.OrderProperties;
import io.github.kanabig.curtaincall.reservation.dto.OrderDetailResponse;
import io.github.kanabig.curtaincall.reservation.dto.OrderResponse;
import io.github.kanabig.curtaincall.reservation.dto.PaymentStartedResponse;
import io.github.kanabig.curtaincall.reservation.entity.HeldSeat;
import io.github.kanabig.curtaincall.reservation.entity.Order;
import io.github.kanabig.curtaincall.reservation.entity.OrderStatus;
import io.github.kanabig.curtaincall.reservation.error.ReservationErrorCode;
import io.github.kanabig.curtaincall.reservation.repository.OrderRepository;
import io.github.kanabig.curtaincall.reservation.repository.OrderSeatRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** [RSV-5] 주문 생성과 결제 시작. 결제 결과 처리는 {@link OrderSagaService}가 맡는다. */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final OrderRepository orders;
    private final OrderSeatRepository seats;
    private final OrderEvents events;
    private final IdempotentRequests idempotency;
    private final OrderProperties properties;
    private final Clock clock;

    public OrderService(
            OrderRepository orders,
            OrderSeatRepository seats,
            OrderEvents events,
            IdempotentRequests idempotency,
            OrderProperties properties,
            Clock clock) {
        this.orders = orders;
        this.seats = seats;
        this.events = events;
        this.idempotency = idempotency;
        this.properties = properties;
        this.clock = clock;
    }

    public OrderResponse createOrder(long userId, String idempotencyKey, String holdId) {
        return idempotency.execute(
                userId, "order:create", idempotencyKey, OrderResponse.class, () -> create(userId, holdId));
    }

    public PaymentStartedResponse startPayment(long userId, String orderId, String idempotencyKey, String cardToken) {
        return idempotency.execute(
                userId,
                "order:pay:" + orderId,
                idempotencyKey,
                PaymentStartedResponse.class,
                () -> beginPayment(userId, orderId, cardToken));
    }

    @Transactional(readOnly = true)
    public OrderDetailResponse get(long userId, String orderId) {
        Order order = orders.find(orderId).orElseThrow(OrderService::notFound);
        if (order.userId() != userId) {
            throw notFound();
        }
        return OrderDetailResponse.from(order);
    }

    /** 점유가 만료된 채 결제를 시작하지 않은 주문을 EXPIRED로 정리한다. 좌석은 건드리지 않는다. */
    @Transactional
    public int expireOverdue() {
        return orders.expirePending(clock.instant());
    }

    private OrderResponse create(long userId, String holdId) {
        if (orders.existsByHold(holdId)) {
            throw new BusinessException(ReservationErrorCode.ORDER_ALREADY_EXISTS);
        }
        List<HeldSeat> held = seats.lockByHold(holdId);
        if (held.isEmpty()
                || held.stream()
                        .anyMatch(s -> !"HELD".equals(s.status())
                                || !Long.valueOf(userId).equals(s.holderId()))) {
            throw new BusinessException(ReservationErrorCode.HOLD_NOT_FOUND);
        }
        Instant now = clock.instant();
        Instant expiresAt = earliestExpiry(held);
        if (!expiresAt.isAfter(now)) {
            throw new BusinessException(ReservationErrorCode.HOLD_EXPIRED);
        }
        Order order = new Order(
                "ord_" + UUID.randomUUID().toString().replace("-", ""),
                holdId,
                userId,
                held.get(0).sessionId(),
                held.stream().map(HeldSeat::seatId).toList(),
                held.stream().mapToLong(HeldSeat::price).sum(),
                OrderStatus.PENDING_PAYMENT,
                expiresAt,
                null,
                null,
                0);
        orders.insert(order);
        return OrderResponse.from(order);
    }

    private PaymentStartedResponse beginPayment(long userId, String orderId, String cardToken) {
        Order order = orders.lock(orderId).orElseThrow(OrderService::notFound);
        if (order.userId() != userId) {
            throw notFound();
        }
        if (order.status() != OrderStatus.PENDING_PAYMENT) {
            throw new BusinessException(ReservationErrorCode.ORDER_NOT_PAYABLE);
        }
        Instant now = clock.instant();
        secureSeatsForPayment(order, now);
        Instant nextResendAt = now.plus(properties.responseTimeout());
        if (!orders.startPayment(orderId, cardToken, now, nextResendAt)) {
            throw new BusinessException(ReservationErrorCode.ORDER_NOT_PAYABLE);
        }
        events.approvePayment(new Order(
                order.orderId(),
                order.holdId(),
                order.userId(),
                order.sessionId(),
                order.seatIds(),
                order.amount(),
                OrderStatus.PAYMENT_REQUESTED,
                order.holdExpiresAt(),
                cardToken,
                null,
                1));
        log.info("결제 시작: orderId={}", orderId);
        return new PaymentStartedResponse(OrderStatus.PAYMENT_REQUESTED);
    }

    /**
     * 결제가 도는 동안 좌석을 지킨다. 남은 시간이 짧으면 거절하고, 아니면 점유를 결제 마감까지 늘린다. 결제 도중 점유가 풀려 같은 사용자가 다른 탭에서 새로 잡은 좌석과
     * 늦은 승인으로 확정된 좌석이 합쳐져 회차당 한도를 넘는 일을 막는다. 주문 상태 전이와 같은 트랜잭션에서 부른다.
     */
    private void secureSeatsForPayment(Order order, Instant now) {
        List<HeldSeat> held = seats.lockByHold(order.holdId());
        long mine = held.stream()
                .filter(s -> "HELD".equals(s.status())
                        && Long.valueOf(order.userId()).equals(s.holderId()))
                .count();
        if (mine < order.seatIds().size()) {
            throw new BusinessException(ReservationErrorCode.HOLD_EXPIRED);
        }
        Instant expiresAt = earliestExpiry(held);
        if (!expiresAt.isAfter(now)) {
            throw new BusinessException(ReservationErrorCode.HOLD_EXPIRED);
        }
        if (expiresAt.isBefore(now.plus(properties.minHoldRemaining()))) {
            throw new BusinessException(ReservationErrorCode.HOLD_EXPIRING);
        }
        int extended = seats.extendForPayment(
                order.holdId(), order.userId(), now, now.plus(properties.paymentHoldExtension()));
        if (extended < order.seatIds().size()) {
            throw new BusinessException(ReservationErrorCode.HOLD_EXPIRED);
        }
    }

    private static Instant earliestExpiry(List<HeldSeat> held) {
        return held.stream().map(HeldSeat::expiresAt).min(Instant::compareTo).orElseThrow();
    }

    private static BusinessException notFound() {
        return new BusinessException(ReservationErrorCode.ORDER_NOT_FOUND);
    }
}
