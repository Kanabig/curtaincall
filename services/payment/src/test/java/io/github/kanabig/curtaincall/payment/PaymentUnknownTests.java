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
    @DisplayName("조회가 제한 시간 내내 404면 망취소 뒤 PaymentFailed(PG_NOT_RECEIVED)로 확정한다")
    void allNotFoundIsNetCancelledAndFailed() throws Exception {
        String orderId = newOrderId();
        PG.hang = true;
        PG.hangMs = 2000;

        deliver(approveCommand(orderId, "tok_approve"));
        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(status(orderId)).isEqualTo("UNKNOWN"));
        exhaustInquiryTime(orderId);

        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(status(orderId)).isEqualTo("FAILED"));
        assertThat(publishedPayload(orderId, "PaymentFailed").get("reason").asText())
                .isEqualTo("PG_NOT_RECEIVED");
        assertThat(PG.cancelRequests()).isEqualTo(1);
        assertThat(PG.isPreCancelled(orderId)).isTrue();
    }

    @Test
    @DisplayName("망취소 뒤 PG가 늦게 승인을 기록하려 해도 거절되어 거래가 생기지 않는다")
    void lateApprovalIsRejectedAfterNetCancel() {
        String orderId = newOrderId();
        // read timeout 500ms보다 긴 지연. PG는 지연이 끝난 뒤에야 승인을 기록하려 한다
        PG.delayMs = 2500;

        deliver(approveCommand(orderId, "tok_approve"));
        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(status(orderId)).isEqualTo("UNKNOWN"));
        exhaustInquiryTime(orderId);

        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(status(orderId)).isEqualTo("FAILED"));
        await().atMost(Duration.ofSeconds(8))
                .untilAsserted(() -> assertThat(PG.hasTransaction(orderId)).isFalse());
        // 지연이 끝난 뒤까지 기다려도 거래가 없다
        await().during(Duration.ofSeconds(3))
                .atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(PG.hasTransaction(orderId)).isFalse());
        assertThat(status(orderId)).isEqualTo("FAILED");
    }

    @Test
    @DisplayName("조회에 오류가 섞였으면 제한 시간이 지나도 망취소하지 않고 UNKNOWN으로 남기며 메트릭이 오른다")
    void mixedInquiryErrorsKeepUnknown() {
        String orderId = newOrderId();
        PG.hang = true;
        PG.hangMs = 2000;
        double before = attentionCount();

        deliver(approveCommand(orderId, "tok_approve"));
        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(status(orderId)).isEqualTo("UNKNOWN"));
        PG.inquiryFail = true;
        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(attempts(orderId)).isGreaterThanOrEqualTo(1));
        PG.inquiryFail = false;
        exhaustInquiryTime(orderId);

        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(nextInquiryIsNull(orderId)).isTrue());
        assertThat(status(orderId)).isEqualTo("UNKNOWN");
        assertThat(PG.cancelRequests()).isZero();
        assertThat(attentionCount() - before).isEqualTo(1.0);
        assertThat(publishedTypes(orderId)).isEmpty();
    }

    @Test
    @DisplayName("망취소가 실패하면 UNKNOWN으로 남고 메트릭이 오르며, 명령이 다시 오면 조회를 처음부터 다시 한다")
    void failedNetCancelKeepsUnknownAndRestartsOnResend() {
        String orderId = newOrderId();
        PG.hang = true;
        PG.hangMs = 2000;
        PG.cancelFail = true;
        double before = attentionCount();

        deliver(approveCommand(orderId, "tok_approve"));
        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(status(orderId)).isEqualTo("UNKNOWN"));
        exhaustInquiryTime(orderId);

        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(PG.cancelRequests()).isEqualTo(1));
        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(attentionCount() - before).isEqualTo(1.0));
        assertThat(status(orderId)).isEqualTo("UNKNOWN");
        assertThat(nextInquiryIsNull(orderId)).isTrue();
        assertThat(publishedTypes(orderId)).isEmpty();

        deliver(approveCommand(orderId, "tok_approve"));

        assertThat(nextInquiryIsNull(orderId)).isFalse();
        assertThat(attempts(orderId)).isZero();
        assertThat(status(orderId)).isEqualTo("UNKNOWN");
        assertThat(PG.approvalRequestsFor(orderId)).isEqualTo(1);
    }

    /** 조회 시작 시각을 제한 시간(테스트 30s)보다 오래전으로 돌려, 다음 조회 miss에서 소진되게 한다. */
    private void exhaustInquiryTime(String orderId) {
        jdbc.update(
                "UPDATE payment SET inquiry_started_at = DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 1 HOUR) WHERE order_id = ?",
                orderId);
    }

    @Test
    @DisplayName("거래 조회가 진행 중일 때 명령이 다시 와도 조회 시작 시각과 시도 횟수는 그대로다")
    void resendDuringInquiryDoesNotRestart() {
        String orderId = newOrderId();
        PG.hang = true;
        PG.hangMs = 2000;

        deliver(approveCommand(orderId, "tok_approve"));
        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(status(orderId)).isEqualTo("UNKNOWN"));
        // 조회 제한 시간(30s) 안쪽의 과거로 돌려 두고, 재전송이 이 값을 건드리는지 본다
        jdbc.update(
                "UPDATE payment SET inquiry_started_at = DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 10 SECOND) WHERE order_id = ?",
                orderId);
        Object startedBefore =
                jdbc.queryForObject("SELECT inquiry_started_at FROM payment WHERE order_id = ?", Object.class, orderId);

        deliver(approveCommand(orderId, "tok_approve"));
        deliver(approveCommand(orderId, "tok_approve"));

        assertThat(nextInquiryIsNull(orderId)).isFalse();
        assertThat(jdbc.queryForObject(
                        "SELECT inquiry_started_at FROM payment WHERE order_id = ?", Object.class, orderId))
                .isEqualTo(startedBefore);
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
