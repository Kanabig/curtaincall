package io.github.kanabig.curtaincall.payment.config;

import io.github.kanabig.curtaincall.common.web.metrics.MetricNames;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedBulkheadMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/** PG 호출에 쓰는 HTTP 클라이언트, 서킷 브레이커, Bulkhead, 호출 전용 실행기. */
@Configuration
@EnableConfigurationProperties(PaymentProperties.class)
public class PgClientConfig {

    /**
     * JDK HttpClient를 쓴다. HttpURLConnection은 POST를 조용히 다시 보낼 수 있어 승인이 두 번 나갈 위험이 있는데, JDK HttpClient는 멱등하지 않은 요청을
     * 재시도하지 않는다. HTTP/1.1로 고정해 h2c 업그레이드 시도를 막는다.
     */
    @Bean
    RestClient pgRestClient(RestClient.Builder builder, PaymentProperties properties) {
        PaymentProperties.Pg pg = properties.pg();
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(pg.connectTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(pg.readTimeout());
        return builder.baseUrl(pg.baseUrl()).requestFactory(requestFactory).build();
    }

    @Bean
    CircuitBreakerRegistry pgCircuitBreakerRegistry(PaymentProperties properties, MeterRegistry meterRegistry) {
        PaymentProperties.Breaker breaker = properties.pg().breaker();
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(breaker.windowSize())
                .minimumNumberOfCalls(breaker.minimumCalls())
                .failureRateThreshold(breaker.failureRateThreshold())
                .waitDurationInOpenState(breaker.openDuration())
                .permittedNumberOfCallsInHalfOpenState(3)
                // PG가 요청 자체를 거절한 4xx는 PG 장애가 아니므로 실패율에 넣지 않는다
                .ignoreExceptions(HttpClientErrorException.class)
                .build();
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(config);
        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(registry).bindTo(meterRegistry);
        return registry;
    }

    @Bean
    CircuitBreaker pgApprovalBreaker(CircuitBreakerRegistry pgCircuitBreakerRegistry) {
        return pgCircuitBreakerRegistry.circuitBreaker("pg-approval");
    }

    /**
     * 승인과 조회·취소의 Bulkhead를 나눈다. 승인이 느려져 가득 차도 조회가 굶지 않아야 UNKNOWN을 풀 수 있다. 가득 차면 기다리지 않고 바로 거절한다(maxWaitDuration 0).
     */
    @Bean
    BulkheadRegistry pgBulkheadRegistry(MeterRegistry meterRegistry) {
        BulkheadRegistry registry = BulkheadRegistry.ofDefaults();
        TaggedBulkheadMetrics.ofBulkheadRegistry(registry).bindTo(meterRegistry);
        return registry;
    }

    @Bean
    Bulkhead pgApprovalBulkhead(
            BulkheadRegistry pgBulkheadRegistry, PaymentProperties properties, MeterRegistry meterRegistry) {
        return bulkhead(pgBulkheadRegistry, "pg-approval", properties.pg().maxConcurrentApprovals(), meterRegistry);
    }

    @Bean
    Bulkhead pgQueryBulkhead(
            BulkheadRegistry pgBulkheadRegistry, PaymentProperties properties, MeterRegistry meterRegistry) {
        return bulkhead(pgBulkheadRegistry, "pg-query", properties.pg().maxConcurrentQueries(), meterRegistry);
    }

    private static Bulkhead bulkhead(BulkheadRegistry registry, String name, int max, MeterRegistry meterRegistry) {
        Bulkhead bulkhead = registry.bulkhead(
                name,
                BulkheadConfig.custom()
                        .maxConcurrentCalls(max)
                        .maxWaitDuration(Duration.ZERO)
                        .build());
        Counter rejected = Counter.builder(MetricNames.PAYMENT_PG_BULKHEAD_REJECTED)
                .tag("bulkhead", name)
                .description("Bulkhead가 거절한 PG 호출 수")
                .register(meterRegistry);
        bulkhead.getEventPublisher().onCallRejected(event -> rejected.increment());
        return bulkhead;
    }

    /**
     * PG 호출을 컨슈머 스레드 밖에서 돌린다. PG가 느려도 컨슈머와 스케줄러가 붙잡히지 않고, 동시에 나가는 호출 수는 Bulkhead가 제한한다.
     */
    @Bean(destroyMethod = "close")
    ExecutorService pgCallExecutor() {
        return Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("pg-call-", 0).factory());
    }
}
