package io.github.kanabig.curtaincall.payment.entity;

/** {@code PaymentFailed}의 reason 값(spec/events.md). */
public enum FailureReason {
    DECLINED,
    NOT_APPROVED,
    PG_UNAVAILABLE
}
