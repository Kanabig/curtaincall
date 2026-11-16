package io.github.kanabig.curtaincall.reservation.entity;

import java.util.Set;

/** 주문 상세의 {@code failureCode} 값. 결제 실패는 {@code PaymentFailed}의 reason을 그대로 쓴다. */
public final class FailureCodes {

    /** 결제를 시작하기 전에 점유가 만료됐다. */
    public static final String HOLD_EXPIRED = "HOLD_EXPIRED";

    /** reason이 없거나 알 수 없는 값일 때. */
    public static final String PAYMENT_FAILED = "PAYMENT_FAILED";

    private static final Set<String> PAYMENT_REASONS =
            Set.of("DECLINED", "PG_REJECTED", "NOT_APPROVED", "PG_UNAVAILABLE", "PG_NOT_RECEIVED");

    private FailureCodes() {}

    public static String fromPaymentReason(String reason) {
        return reason != null && PAYMENT_REASONS.contains(reason) ? reason : PAYMENT_FAILED;
    }
}
