package io.github.kanabig.curtaincall.reservation.entity;

import java.util.EnumSet;
import java.util.Set;

/** 주문 상태이자 Saga 상태. 허용된 전이만 {@link #requireTransitionTo}를 통과한다. */
public enum OrderStatus {
    PENDING_PAYMENT,
    PAYMENT_REQUESTED,
    CONFIRMED,
    FAILED,
    EXPIRED,
    REFUNDING,
    REFUNDED;

    public Set<OrderStatus> next() {
        return switch (this) {
            case PENDING_PAYMENT -> EnumSet.of(PAYMENT_REQUESTED, EXPIRED);
            case PAYMENT_REQUESTED -> EnumSet.of(CONFIRMED, FAILED, REFUNDING);
            case REFUNDING -> EnumSet.of(REFUNDED);
            case CONFIRMED, FAILED, EXPIRED, REFUNDED -> EnumSet.noneOf(OrderStatus.class);
        };
    }

    public boolean canTransitionTo(OrderStatus target) {
        return next().contains(target);
    }

    public void requireTransitionTo(OrderStatus target) {
        if (!canTransitionTo(target)) {
            throw new IllegalStateException("허용되지 않는 주문 상태 전이: %s -> %s".formatted(this, target));
        }
    }

    /** 더 바뀌지 않는 상태. 결제 응답이 늦게 와도 무시한다. */
    public boolean isTerminal() {
        return next().isEmpty();
    }
}
