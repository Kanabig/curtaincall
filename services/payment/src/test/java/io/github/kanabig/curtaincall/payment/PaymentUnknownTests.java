package io.github.kanabig.curtaincall.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** PAY-4 결과 불명(타임아웃·응답 유실)과 거래 조회. */
class PaymentUnknownTests extends PaymentIntegrationTest {

    @Test
    @DisplayName("응답 유실(premature EOF)이면 UNKNOWN이 되고, 거래 조회로 APPROVED가 된다")
    void droppedResponseConvergesToApproved() {
        String orderId = newOrderId();
        PG.dropResponse = true;

        deliver(approveCommand(orderId, "tok_approve"));

        // UNKNOWN을 거쳐 조회로 승인이 확인된다. 그동안 승인 요청은 한 번만 나간다
        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(() -> assertThat(status(orderId)).isEqualTo("APPROVED"));
        assertThat(PG.approvalRequestsFor(orderId)).isEqualTo(1);
        assertThat(publishedTypes(orderId)).containsExactly("PaymentApproved");
    }

    @Test
    @DisplayName("PG 지연이 read timeout보다 길면 UNKNOWN이 되고, 조회가 404인 동안 UNKNOWN을 유지하다가 기록되면 APPROVED가 된다")
    void delayLongerThanReadTimeoutConverges() {
        String orderId = newOrderId();
        // read timeout 500ms의 6배
        PG.delayMs = 3000;

        deliver(approveCommand(orderId, "tok_approve"));

        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(status(orderId)).isEqualTo("UNKNOWN"));
        // 조회가 404를 주는 구간: FAILED로 바뀌지 않고 이벤트도 나가지 않는다
        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(attempts(orderId)).isGreaterThanOrEqualTo(1));
        assertThat(status(orderId)).isEqualTo("UNKNOWN");
        assertThat(publishedTypes(orderId)).isEmpty();

        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(() -> assertThat(status(orderId)).isEqualTo("APPROVED"));
        assertThat(PG.approvalRequestsFor(orderId)).isEqualTo(1);
        assertThat(publishedTypes(orderId)).containsExactly("PaymentApproved");
    }

    @Test
    @DisplayName("PG가 5xx를 주면 결과를 모르므로 UNKNOWN이다")
    void serverErrorIsUnknown() {
        String orderId = newOrderId();
        PG.fail = true;

        deliver(approveCommand(orderId, "tok_approve"));

        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(status(orderId)).isEqualTo("UNKNOWN"));
        assertThat(publishedTypes(orderId)).isEmpty();
    }

    @Test
    @DisplayName("PG가 승인을 기록하지 못한 채(404) 조회 제한 시간이 지나면 UNKNOWN으로 남고 메트릭이 오르며, 명령이 다시 오면 조회를 처음부터 다시 한다")
    void exhaustedInquiryKeepsUnknownAndRestartsOnResend() {
        String orderId = newOrderId();
        PG.hang = true;
        PG.hangMs = 2000;
        double before = attentionCount();

        deliver(approveCommand(orderId, "tok_approve"));
        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(status(orderId)).isEqualTo("UNKNOWN"));
        // 조회 시작 시각을 제한 시간(30s)보다 오래전으로 돌려 다음 조회 miss에서 멈추게 한다
        jdbc.update(
                "UPDATE payment SET inquiry_started_at = DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 1 HOUR) WHERE order_id = ?",
                orderId);

        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(nextInquiryIsNull(orderId)).isTrue());
        assertThat(status(orderId)).isEqualTo("UNKNOWN");
        assertThat(attentionCount() - before).isEqualTo(1.0);
        assertThat(publishedTypes(orderId)).isEmpty();

        deliver(approveCommand(orderId, "tok_approve"));

        assertThat(nextInquiryIsNull(orderId)).isFalse();
        assertThat(attempts(orderId)).isZero();
        assertThat(status(orderId)).isEqualTo("UNKNOWN");
        assertThat(PG.approvalRequestsFor(orderId)).isEqualTo(1);
    }

    @Test
    @DisplayName("승인 호출 도중 멈춘 REQUESTED는 UNKNOWN을 거쳐 조회로 확정된다")
    void staleRequestedIsRecovered() {
        String orderId = newOrderId();
        PG.seed(orderId, 120000);
        jdbc.update("""
                INSERT INTO payment (payment_id, order_id, user_id, amount, card_token, status, created_at, updated_at)
                VALUES (?, ?, '7', 120000, 'tok_approve', 'REQUESTED',
                        DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 1 HOUR), DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 1 HOUR))
                """, "pay_" + orderId, orderId);

        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(() -> assertThat(status(orderId)).isEqualTo("APPROVED"));
        assertThat(PG.approvalRequestsFor(orderId)).isZero();
        assertThat(publishedTypes(orderId)).containsExactly("PaymentApproved");
    }

    private int attempts(String orderId) {
        Integer value =
                jdbc.queryForObject("SELECT inquiry_attempts FROM payment WHERE order_id = ?", Integer.class, orderId);
        return value == null ? 0 : value;
    }

    private boolean nextInquiryIsNull(String orderId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT next_inquiry_at IS NULL FROM payment WHERE order_id = ?", Boolean.class, orderId));
    }

    private double attentionCount() {
        return meterRegistry.counter("payment.attention", "status", "UNKNOWN").count();
    }
}
