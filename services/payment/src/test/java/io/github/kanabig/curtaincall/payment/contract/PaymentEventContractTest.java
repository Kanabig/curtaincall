package io.github.kanabig.curtaincall.payment.contract;

import io.github.kanabig.curtaincall.outbox.message.EventEnvelopes;
import io.github.kanabig.curtaincall.payment.dto.PaymentApprovedEvent;
import io.github.kanabig.curtaincall.payment.dto.PaymentCancelledEvent;
import io.github.kanabig.curtaincall.payment.dto.PaymentFailedEvent;
import io.github.kanabig.curtaincall.testsupport.EventContract;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;
import tools.jackson.databind.ObjectMapper;

/** payment.payment 이벤트의 와이어 형식. 골든은 libs/test-support의 contract/. */
@JsonTest
class PaymentEventContractTest {

    @Autowired
    ObjectMapper mapper;

    private String write(String type, Object body) {
        return new EventEnvelopes(mapper, EventContract.CLOCK).write(EventContract.EVENT_ID, type, "ord-1", body);
    }

    @Test
    @DisplayName("PaymentApproved")
    void approved() {
        // 밀리초 아래 자릿수가 있는 시각도 같은 형식으로 나가야 한다
        PaymentApprovedEvent body =
                new PaymentApprovedEvent("pay-1", "ord-1", 132000L, Instant.parse("2026-12-01T11:03:11.123456Z"));
        EventContract.assertMatches("PaymentApproved", write("PaymentApproved", body));
    }

    @Test
    @DisplayName("PaymentFailed")
    void failed() {
        PaymentFailedEvent body = new PaymentFailedEvent(
                "pay-1", "ord-1", io.github.kanabig.curtaincall.payment.entity.FailureReason.DECLINED);
        EventContract.assertMatches("PaymentFailed", write("PaymentFailed", body));
    }

    @Test
    @DisplayName("PaymentCancelled")
    void cancelled() {
        PaymentCancelledEvent body = new PaymentCancelledEvent(
                "pay-1", "ord-1", 132000L, io.github.kanabig.curtaincall.payment.entity.CancelReason.SEAT_LOST);
        EventContract.assertMatches("PaymentCancelled", write("PaymentCancelled", body));
    }
}
