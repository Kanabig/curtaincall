package io.github.kanabig.curtaincall.payment.entity;

/** 결제 상태. UNKNOWN은 PG가 승인했는지 모르는 상태라 FAILED로 바꾸지 않고 거래 조회로만 확정한다. */
public enum PaymentStatus {
    REQUESTED,
    APPROVED,
    FAILED,
    UNKNOWN
}
