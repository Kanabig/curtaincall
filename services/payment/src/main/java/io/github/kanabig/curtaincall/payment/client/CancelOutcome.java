package io.github.kanabig.curtaincall.payment.client;

/** PG 거래 취소의 결과. 취소는 PG에서 멱등이라 실패는 다시 시도할 수 있다. */
public sealed interface CancelOutcome {

    /** 취소됐다(이미 취소된 거래 포함). */
    record Cancelled() implements CancelOutcome {}

    /** 취소를 확인하지 못했다(타임아웃, 5xx, 4xx, Bulkhead 거절). */
    record Failed(String detail) implements CancelOutcome {}
}
