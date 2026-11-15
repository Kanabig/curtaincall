package io.github.kanabig.curtaincall.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** PAY-5 결제 취소(환불). */
class PaymentCancelTests extends PaymentIntegrationTest {

    private String approvedOrder() {
        String orderId = newOrderId();
        deliver(approveCommand(orderId, "tok_approve"));
        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(status(orderId)).isEqualTo("APPROVED"));
        return orderId;
    }

    private void awaitStatus(String orderId, String status) {
        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(() -> assertThat(status(orderId)).isEqualTo(status));
    }

    private double attention() {
        return meterRegistry
                .counter("payment.attention", "status", "CANCEL_FAILED")
                .count();
    }

    @Test
    @DisplayName("승인된 결제의 취소 명령은 PG 취소 뒤 PaymentCancelled를 낸다")
    void cancelsApprovedPayment() throws Exception {
        String orderId = approvedOrder();

        deliver(cancelCommand(orderId, "SEAT_LOST"));

        awaitStatus(orderId, "CANCELLED");
        assertThat(publishedTypes(orderId)).containsExactly("PaymentApproved", "PaymentCancelled");
        assertThat(publishedPayload(orderId, "PaymentCancelled").get("reason").asText())
                .isEqualTo("SEAT_LOST");
        assertThat(PG.cancelledTransactions()).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 취소 명령이 중복돼도 PG 환불은 1건이고, 이미 CANCELLED면 같은 응답을 다시 낸다")
    void duplicateCancelRefundsOnce() {
        String orderId = approvedOrder();

        deliver(cancelCommand(orderId, "USER_CANCEL"));
        deliver(cancelCommand(orderId, "USER_CANCEL"));
        awaitStatus(orderId, "CANCELLED");
        int pgRequests = PG.tidCancelRequests();
        deliver(cancelCommand(orderId, "USER_CANCEL"));

        assertThat(PG.tidCancelRequests()).isEqualTo(pgRequests);
        assertThat(PG.cancelledTransactions()).isEqualTo(1);
        assertThat(publishedTypes(orderId)).containsExactly("PaymentApproved", "PaymentCancelled", "PaymentCancelled");
    }

    @Test
    @DisplayName("PG 취소가 몇 번 실패해도 재시도로 CANCELLED에 수렴한다")
    void retriesUntilCancelled() {
        String orderId = approvedOrder();
        PG.tidCancelFailures.set(3);

        deliver(cancelCommand(orderId, "SEAT_LOST"));

        awaitStatus(orderId, "CANCELLED");
        assertThat(PG.tidCancelRequests()).isEqualTo(4);
        assertThat(PG.cancelledTransactions()).isEqualTo(1);
        assertThat(publishedTypes(orderId)).containsExactly("PaymentApproved", "PaymentCancelled");
    }

    @Test
    @DisplayName("취소 응답이 유실돼도 거래 조회로 취소를 확인하고 PG에 다시 취소하지 않는다")
    void lostResponseIsConfirmedByInquiry() {
        String orderId = approvedOrder();
        PG.tidCancelDropResponse = true;

        deliver(cancelCommand(orderId, "SEAT_LOST"));

        awaitStatus(orderId, "CANCELLED");
        assertThat(PG.tidCancelRequests()).isEqualTo(1);
        assertThat(PG.cancelledTransactions()).isEqualTo(1);
    }

    @Test
    @DisplayName("재시도 한도를 넘으면 CANCEL_FAILED로 두고 메트릭을 올리며 응답은 보내지 않는다")
    void exhaustedRetriesBecomeCancelFailed() {
        String orderId = approvedOrder();
        PG.tidCancelFailures.set(1000);
        double before = attention();

        deliver(cancelCommand(orderId, "SEAT_LOST"));

        awaitStatus(orderId, "CANCEL_FAILED");
        assertThat(PG.tidCancelRequests()).isEqualTo(5);
        assertThat(attention() - before).isEqualTo(1.0);
        assertThat(publishedTypes(orderId)).containsExactly("PaymentApproved");
    }

    @Test
    @DisplayName("CANCEL_FAILED는 취소 명령이 다시 오면 처음부터 다시 시도해 취소한다")
    void cancelFailedRestartsOnRedelivery() {
        String orderId = approvedOrder();
        PG.tidCancelFailures.set(1000);
        deliver(cancelCommand(orderId, "SEAT_LOST"));
        awaitStatus(orderId, "CANCEL_FAILED");
        PG.tidCancelFailures.set(0);

        deliver(cancelCommand(orderId, "SEAT_LOST"));

        awaitStatus(orderId, "CANCELLED");
        assertThat(publishedTypes(orderId)).containsExactly("PaymentApproved", "PaymentCancelled");
    }

    @Test
    @DisplayName("다음 시도 예약이 사라진 CANCELLING은 명령이 다시 오면 재시작한다")
    void stalledCancellingRestarts() {
        String orderId = approvedOrder();
        jdbc.update(
                "UPDATE payment SET status = 'CANCELLING', cancel_reason = 'SEAT_LOST', next_cancel_at = NULL"
                        + " WHERE order_id = ?",
                orderId);

        deliver(cancelCommand(orderId, "SEAT_LOST"));

        awaitStatus(orderId, "CANCELLED");
    }

    @Test
    @DisplayName("프로세스가 멈춰 임대가 지난 CANCELLING은 스케줄러가 이어서 취소한다")
    void leaseExpiredCancellingIsResumed() {
        String orderId = approvedOrder();
        jdbc.update(
                "UPDATE payment SET status = 'CANCELLING', cancel_reason = 'USER_CANCEL',"
                        + " next_cancel_at = DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 1 SECOND) WHERE order_id = ?",
                orderId);

        awaitStatus(orderId, "CANCELLED");
        assertThat(publishedTypes(orderId)).containsExactly("PaymentApproved", "PaymentCancelled");
    }

    @Test
    @DisplayName("승인 전이거나 실패한 결제의 취소 명령은 응답 없이 끝난다")
    void cancelWithoutApprovalGetsNoReply() {
        String orderId = newOrderId();
        PG.hang = true;
        PG.hangMs = 2000;
        deliver(approveCommand(orderId, "tok_approve"));
        awaitStatus(orderId, "UNKNOWN");

        deliver(cancelCommand(orderId, "SEAT_LOST"));

        assertThat(status(orderId)).isEqualTo("UNKNOWN");
        assertThat(PG.tidCancelRequests()).isZero();
        assertThat(publishedTypes(orderId)).isEmpty();
    }
}
