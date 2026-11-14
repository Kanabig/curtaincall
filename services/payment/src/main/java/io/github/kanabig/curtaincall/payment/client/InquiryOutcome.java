package io.github.kanabig.curtaincall.payment.client;

import java.time.Instant;

/** PG 거래 조회의 결과. */
public sealed interface InquiryOutcome {

    record Found(String tid, String status, String failureCode, Instant createdAt) implements InquiryOutcome {

        public boolean isApproved() {
            return "APPROVED".equals(status);
        }

        public boolean isDeclined() {
            return "DECLINED".equals(status);
        }
    }

    /** PG가 해당 주문을 받은 기록이 없다고 답했다. 승인 요청이 PG에 닿지 않았을 수도, 아직 기록 전일 수도 있어 실패로 단정하지 않는다. */
    record NotFound() implements InquiryOutcome {}

    /** 조회 자체를 못 했다(타임아웃, 5xx, Bulkhead 거절). */
    record Failed(String detail) implements InquiryOutcome {}
}
