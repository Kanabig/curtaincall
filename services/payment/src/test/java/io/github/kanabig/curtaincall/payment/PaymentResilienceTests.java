package io.github.kanabig.curtaincall.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;

/** NFR-9 PG 지연·장애에서 payment의 다른 처리 보호: 서킷 브레이커와 Bulkhead. */
class PaymentResilienceTests extends PaymentIntegrationTest {

    @Autowired
    TestRestTemplate restTemplate;

    @Autowired
    Bulkhead pgApprovalBulkhead;

    @Test
    @DisplayName("최근 20건이 모두 실패하면 서킷이 열리고, 이후 승인은 PG에 보내지 않고 PaymentFailed(PG_UNAVAILABLE)로 끝난다")
    void openCircuitFailsFastWithoutSending() throws Exception {
        PG.fail = true;
        for (int i = 0; i < 20; i++) {
            deliver(approveCommand(newOrderId(), "tok_approve"));
        }
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(pgApprovalBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN));
        int sentBefore = PG.totalApprovalRequests();
        String orderId = newOrderId();

        deliver(approveCommand(orderId, "tok_approve"));

        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(status(orderId)).isEqualTo("FAILED"));
        JsonNode payload = publishedPayload(orderId, "PaymentFailed");
        assertThat(payload.get("reason").asText()).isEqualTo("PG_UNAVAILABLE");
        assertThat(PG.approvalRequestsFor(orderId)).isZero();
        assertThat(PG.totalApprovalRequests()).isEqualTo(sentBefore);
        // 서킷 상태와 UNKNOWN 건수가 지표로 나온다
        assertThat(meterRegistry
                        .get("resilience4j.circuitbreaker.state")
                        .tag("name", "pg-approval")
                        .tag("state", "open")
                        .gauge()
                        .value())
                .isEqualTo(1.0);
        assertThat(meterRegistry.get("payment.unknown").gauge().value()).isEqualTo(20.0);
    }

    @Test
    @DisplayName("승인 Bulkhead가 가득 차도 조회는 별도 Bulkhead라 통과해 UNKNOWN이 풀리고, 새 승인은 거절된다")
    void queriesAreNotStarvedByApprovals() {
        Bulkhead approvalBulkhead = pgApprovalBulkhead;
        int permits = 0;
        while (approvalBulkhead.tryAcquirePermission()) {
            permits++;
        }
        try {
            assertThat(permits).isEqualTo(20);
            String unknownOrder = newOrderId();
            PG.seed(unknownOrder, 120000);
            jdbc.update("""
                    INSERT INTO payment (payment_id, order_id, user_id, amount, card_token, status,
                                         inquiry_started_at, next_inquiry_at, created_at, updated_at)
                    VALUES (?, ?, '7', 120000, 'tok_approve', 'UNKNOWN',
                            UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                    """, "pay_" + unknownOrder, unknownOrder);

            await().atMost(Duration.ofSeconds(5))
                    .untilAsserted(() -> assertThat(status(unknownOrder)).isEqualTo("APPROVED"));

            String newOrder = newOrderId();
            deliver(approveCommand(newOrder, "tok_approve"));
            await().atMost(Duration.ofSeconds(5))
                    .untilAsserted(() -> assertThat(status(newOrder)).isEqualTo("FAILED"));
            assertThat(PG.approvalRequestsFor(newOrder)).isZero();
        } finally {
            for (int i = 0; i < permits; i++) {
                approvalBulkhead.onComplete();
            }
        }
    }

    @Test
    @DisplayName("PG가 응답하지 않아도 동시 호출은 20개로 제한되고 나머지는 바로 거절되며, 컨슈머 처리와 HTTP는 느려지지 않는다")
    void bulkheadLimitsPgCallsAndKeepsOtherWorkResponsive() throws Exception {
        PG.hang = true;
        PG.hangMs = 3000;
        double rejectedBefore = meterRegistry
                .counter("payment.pg.bulkhead.rejected", "bulkhead", "pg-approval")
                .count();
        List<String> orderIds = new ArrayList<>();
        List<CompletableFuture<Void>> deliveries = new ArrayList<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(40)) {
            for (int i = 0; i < 40; i++) {
                String orderId = newOrderId();
                orderIds.add(orderId);
                deliveries.add(
                        CompletableFuture.runAsync(() -> deliver(approveCommand(orderId, "tok_approve")), executor));
            }
            CompletableFuture.allOf(deliveries.toArray(CompletableFuture[]::new))
                    .get();
        }

        // PG 호출이 매달려 있는 동안에도 다른 처리는 바로 끝난다
        long start = System.nanoTime();
        assertThat(restTemplate.getForEntity("/actuator/health", String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        deliver(approveCommand(newOrderId(), "tok_approve"));
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
        assertThat(elapsed).isLessThan(Duration.ofMillis(1000));

        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(meterRegistry
                                        .counter("payment.pg.bulkhead.rejected", "bulkhead", "pg-approval")
                                        .count()
                                - rejectedBefore)
                        .isGreaterThanOrEqualTo(1.0));
        // 거절된 건은 PG에 보내지 않았으므로 FAILED(PG_UNAVAILABLE)로 확정된다
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(jdbc.queryForObject(
                                "SELECT COUNT(*) FROM payment WHERE status = 'FAILED' AND failure_reason = 'PG_UNAVAILABLE'",
                                Integer.class))
                        .isGreaterThanOrEqualTo(1));
        // 매달린 호출이 모두 끝난 뒤 테스트를 마친다. 다음 테스트가 테이블을 비울 때 진행 중인 호출이 남지 않게 한다
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(jdbc.queryForObject(
                                "SELECT COUNT(*) FROM payment WHERE status = 'REQUESTED'", Integer.class))
                        .isZero());
    }
}
