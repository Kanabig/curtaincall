package io.github.kanabig.curtaincall.reservation.entity;

import java.util.EnumSet;
import java.util.Set;

/** 예매 상태. 취소는 환불이 끝나야 CANCELLED가 되므로 중간에 CANCEL_REQUESTED를 거친다. */
public enum ReservationStatus {
    CONFIRMED,
    CANCEL_REQUESTED,
    CANCELLED;

    public Set<ReservationStatus> next() {
        return switch (this) {
            case CONFIRMED -> EnumSet.of(CANCEL_REQUESTED);
            case CANCEL_REQUESTED -> EnumSet.of(CANCELLED);
            case CANCELLED -> EnumSet.noneOf(ReservationStatus.class);
        };
    }

    public void requireTransitionTo(ReservationStatus target) {
        if (!next().contains(target)) {
            throw new IllegalStateException("허용되지 않는 예매 상태 전이: %s -> %s".formatted(this, target));
        }
    }
}
