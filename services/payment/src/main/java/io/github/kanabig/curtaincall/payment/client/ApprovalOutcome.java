package io.github.kanabig.curtaincall.payment.client;

import java.time.Instant;

/** PG 승인 호출의 결과. 응답을 받지 못한 경우는 {@link Unknown}이고, 호출을 보내지 않은 경우만 {@link NotSent}다. */
public sealed interface ApprovalOutcome {

    record Approved(String tid, Instant approvedAt) implements ApprovalOutcome {}

    record Declined(String failureCode) implements ApprovalOutcome {}

    /** 타임아웃, 응답 유실, 5xx 등. PG가 승인했을 수 있다. */
    record Unknown(String detail) implements ApprovalOutcome {}

    /** 서킷이 열렸거나 Bulkhead가 가득 차 PG에 보내지 않았다. 보내지 않았으므로 실패로 확정해도 안전하다. */
    record NotSent(Reason reason) implements ApprovalOutcome {}

    enum Reason {
        CIRCUIT_OPEN,
        BULKHEAD_FULL
    }
}
