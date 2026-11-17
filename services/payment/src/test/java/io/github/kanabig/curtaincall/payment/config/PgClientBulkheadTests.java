package io.github.kanabig.curtaincall.payment.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.kanabig.curtaincall.payment.client.ApprovalOutcome;
import io.github.kanabig.curtaincall.payment.client.PgClient;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.web.client.RestClient;

/** 승인 Bulkhead의 maxWait 동작. PG 응답은 latch로 붙잡았다가 푼다. */
class PgClientBulkheadTests {

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final CountDownLatch release = new CountDownLatch(1);
    private final AtomicInteger sent = new AtomicInteger();
    private final CountDownLatch firstSent = new CountDownLatch(1);
    private CircuitBreaker breaker;

    @BeforeEach
    void setUp() {
        breaker = CircuitBreaker.ofDefaults("pg-approval-test");
    }

    private PgClient client(Duration maxWait) {
        RestClient restClient = RestClient.builder()
                .requestFactory((uri, method) -> new MockClientHttpRequest(method, uri) {
                    @Override
                    protected ClientHttpResponse executeInternal() throws IOException {
                        sent.incrementAndGet();
                        firstSent.countDown();
                        try {
                            release.await(10, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        MockClientHttpResponse response = new MockClientHttpResponse(
                                "{\"tid\":\"t1\",\"merchantOrderId\":\"o\",\"status\":\"APPROVED\",\"amount\":1,\"duplicate\":false}"
                                        .getBytes(),
                                200);
                        response.getHeaders().add(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
                        return response;
                    }
                })
                .build();
        BulkheadRegistry registry = BulkheadRegistry.ofDefaults();
        Bulkhead approval = PgClientConfig.bulkhead(registry, "pg-approval", 1, maxWait, meters);
        Bulkhead query = PgClientConfig.bulkhead(registry, "pg-query", 1, Duration.ZERO, meters);
        return new PgClient(restClient, approval, query, breaker, meters);
    }

    private CompletableFuture<ApprovalOutcome> approveAsync(PgClient client) {
        return CompletableFuture.supplyAsync(() -> client.approve("o", 1, "tok"));
    }

    private double waitCount() {
        return meters.get("payment.pg.bulkhead.wait").timer().count();
    }

    @Test
    @DisplayName("슬롯이 가득 차도 maxWait 안에 비면 기다렸다가 PG에 보낸다")
    void waitsForSlotWithinMaxWait() throws Exception {
        PgClient client = client(Duration.ofSeconds(5));
        CompletableFuture<ApprovalOutcome> first = approveAsync(client);
        assertThat(firstSent.await(5, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<ApprovalOutcome> second = approveAsync(client);
        Thread.sleep(300);
        assertThat(second).isNotDone();

        release.countDown();

        assertThat(first.get(5, TimeUnit.SECONDS)).isInstanceOf(ApprovalOutcome.Approved.class);
        assertThat(second.get(5, TimeUnit.SECONDS)).isInstanceOf(ApprovalOutcome.Approved.class);
        assertThat(sent.get()).isEqualTo(2);
        assertThat(waitCount()).isEqualTo(2);
        assertThat(meters.get("payment.pg.bulkhead.wait").timer().max(TimeUnit.MILLISECONDS))
                .isGreaterThanOrEqualTo(250);
    }

    @Test
    @DisplayName("maxWait 안에 슬롯이 나지 않으면 PG에 보내지 않고 BULKHEAD_FULL로 거절한다")
    void rejectsAfterMaxWait() throws Exception {
        PgClient client = client(Duration.ofMillis(300));
        CompletableFuture<ApprovalOutcome> first = approveAsync(client);
        assertThat(firstSent.await(5, TimeUnit.SECONDS)).isTrue();

        long start = System.nanoTime();
        ApprovalOutcome second = client.approve("o2", 1, "tok");
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        assertThat(second).isEqualTo(new ApprovalOutcome.NotSent(ApprovalOutcome.Reason.BULKHEAD_FULL));
        assertThat(elapsed).isGreaterThanOrEqualTo(Duration.ofMillis(250));
        assertThat(sent.get()).isEqualTo(1);
        assertThat(meters.get("payment.pg.bulkhead.rejected")
                        .tag("bulkhead", "pg-approval")
                        .counter()
                        .count())
                .isEqualTo(1);
        release.countDown();
        first.get(5, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("기다리는 동안 서킷이 열리면, 슬롯을 얻은 뒤 PG에 보내지 않고 CIRCUIT_OPEN으로 끝난다")
    void circuitOpenedWhileWaitingFailsFast() throws Exception {
        PgClient client = client(Duration.ofSeconds(5));
        CompletableFuture<ApprovalOutcome> first = approveAsync(client);
        assertThat(firstSent.await(5, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<ApprovalOutcome> second = approveAsync(client);
        Thread.sleep(300);
        assertThat(second).isNotDone();

        breaker.transitionToOpenState();
        release.countDown();

        assertThat(first.get(5, TimeUnit.SECONDS)).isInstanceOf(ApprovalOutcome.Approved.class);
        assertThat(second.get(5, TimeUnit.SECONDS))
                .isEqualTo(new ApprovalOutcome.NotSent(ApprovalOutcome.Reason.CIRCUIT_OPEN));
        assertThat(sent.get()).isEqualTo(1);
    }
}
