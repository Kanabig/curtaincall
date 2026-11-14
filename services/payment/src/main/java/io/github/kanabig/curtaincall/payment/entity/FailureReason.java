package io.github.kanabig.curtaincall.payment.entity;

/** {@code PaymentFailed}의 reason 값(spec/events.md). */
public enum FailureReason {
    DECLINED,
    NOT_APPROVED,
    PG_UNAVAILABLE,
    /** PG가 요청 자체를 거절했다(4xx). */
    PG_REJECTED,
    /** 거래 조회가 끝까지 "거래 없음"이라 망취소하고 실패로 확정했다. */
    PG_NOT_RECEIVED
}
