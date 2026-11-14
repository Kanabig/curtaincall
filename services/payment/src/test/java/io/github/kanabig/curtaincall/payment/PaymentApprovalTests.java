package io.github.kanabig.curtaincall.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** PAY-1 승인, PAY-3 멱등성(NFR-2), PAY-6 결과 이벤트. */
class PaymentApprovalTests extends PaymentIntegrationTest {

    @Test
    @DisplayName("승인 명령을 처리하면 PG에 한 번 승인하고 PaymentApproved를 outbox에 기록한다")
    void approves() throws Exception {
        String orderId = newOrderId();

        deliver(approveCommand(orderId, "tok_approve"));

        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(status(orderId)).isEqualTo("APPROVED"));
        assertThat(publishedTypes(orderId)).containsExactly("PaymentApproved");
        JsonNode payload = publishedPayload(orderId, "PaymentApproved");
        assertThat(payload.get("orderId").asText()).isEqualTo(orderId);
        assertThat(payload.get("paymentId").asText()).startsWith("pay_");
        assertThat(payload.get("amount").asLong()).isEqualTo(120000);
        assertThat(payload.get("approvedAt").asText()).isNotBlank();
        assertThat(PG.approvalRequestsFor(orderId)).isEqualTo(1);
    }

    @Test
    @DisplayName("PG가 거절하면 FAILED로 두고 PaymentFailed(DECLINED)를 기록한다")
    void declined() throws Exception {
        String orderId = newOrderId();

        deliver(approveCommand(orderId, "tok_decline"));

        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(status(orderId)).isEqualTo("FAILED"));
        assertThat(publishedTypes(orderId)).containsExactly("PaymentFailed");
        assertThat(publishedPayload(orderId, "PaymentFailed").get("reason").asText())
                .isEqualTo("DECLINED");
    }

    @Test
    @DisplayName("같은 ApprovePayment를 10번 보내도 PG 승인은 1건이고 결제 행도 하나다")
    void tenSequentialCommandsApproveOnce() {
        String orderId = newOrderId();

        for (int i = 0; i < 10; i++) {
            deliver(approveCommand(orderId, "tok_approve"));
        }

        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(status(orderId)).isEqualTo("APPROVED"));
        assertThat(PG.approvalRequestsFor(orderId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment WHERE order_id = ?", Integer.class, orderId))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("같은 주문의 명령이 동시에 와도 PG 승인은 1건이다(유니크 위반은 컨슈머 재시도로 흡수)")
    void tenConcurrentCommandsApproveOnce() throws Exception {
        String orderId = newOrderId();
        List<CompletableFuture<Void>> deliveries = new ArrayList<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(10)) {
            for (int i = 0; i < 10; i++) {
                deliveries.add(CompletableFuture.runAsync(() -> deliverWithRetry(orderId), executor));
            }
            CompletableFuture.allOf(deliveries.toArray(CompletableFuture[]::new))
                    .get();
        }

        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(status(orderId)).isEqualTo("APPROVED"));
        assertThat(PG.approvalRequestsFor(orderId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment WHERE order_id = ?", Integer.class, orderId))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("이미 승인된 주문의 명령이 다시 오면 PG를 부르지 않고 현재 결과를 다시 응답한다")
    void resendRepliesWithCurrentResult() {
        String orderId = newOrderId();
        deliver(approveCommand(orderId, "tok_approve"));
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(status(orderId)).isEqualTo("APPROVED"));

        deliver(approveCommand(orderId, "tok_approve"));

        assertThat(publishedTypes(orderId)).containsExactly("PaymentApproved", "PaymentApproved");
        assertThat(PG.approvalRequestsFor(orderId)).isEqualTo(1);
    }

    @Test
    @DisplayName("거절된 주문의 명령이 다시 오면 PaymentFailed를 다시 응답한다")
    void resendRepliesWithFailure() {
        String orderId = newOrderId();
        deliver(approveCommand(orderId, "tok_decline"));
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(status(orderId)).isEqualTo("FAILED"));

        deliver(approveCommand(orderId, "tok_decline"));

        assertThat(publishedTypes(orderId)).containsExactly("PaymentFailed", "PaymentFailed");
        assertThat(PG.approvalRequestsFor(orderId)).isEqualTo(1);
    }

    /** 컨슈머는 예외가 나면 같은 레코드를 다시 처리한다. 유니크 위반 뒤의 재시도를 흉내 낸다. */
    private void deliverWithRetry(String orderId) {
        var command = approveCommand(orderId, "tok_approve");
        for (int attempt = 0; attempt < 5; attempt++) {
            try {
                deliver(command);
                return;
            } catch (RuntimeException e) {
                // 다른 스레드가 먼저 결제 행을 만들었다. 다시 처리하면 기존 행을 본다
            }
        }
        throw new IllegalStateException("재시도 한도 초과");
    }
}
