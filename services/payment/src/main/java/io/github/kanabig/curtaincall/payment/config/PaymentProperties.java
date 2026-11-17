package io.github.kanabig.curtaincall.payment.config;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** {@code payment.*} 설정. 기본값은 PG 회복력 기준(timeout, 재시도, Bulkhead)이다. */
@ConfigurationProperties(prefix = "payment", ignoreUnknownFields = false)
public record PaymentProperties(
        @DefaultValue Pg pg,
        @DefaultValue Inquiry inquiry,
        @DefaultValue Cancel cancel,
        @DefaultValue CommandListener commandListener) {

    /** 명령 리스너 자동 시작 여부. 값은 {@code @KafkaListener}의 autoStartup 플레이스홀더가 읽고, 여기서는 키를 선언해 오타 검사에 걸리지 않게 한다. */
    public record CommandListener(@DefaultValue("true") boolean autoStart) {}

    public record Pg(
            @DefaultValue("http://localhost:8090") String baseUrl,
            @DefaultValue("1s") Duration connectTimeout,
            @DefaultValue("5s") Duration readTimeout,
            @DefaultValue("50") int maxConcurrentApprovals,
            @DefaultValue("1s") Duration approvalMaxWait,
            @DefaultValue("10") int maxConcurrentQueries,
            @DefaultValue Breaker breaker) {

        /**
         * 승인 Bulkhead는 가득 차면 approvalMaxWait까지 기다린 뒤 거절한다. 기다리는 쪽은 가상 스레드라 컨슈머를 잡지 않는다. 조회·취소 Bulkhead는 기다리지 않고
         * 바로 거절한다(설정 없음).
         */
        public Pg {
            if (maxConcurrentApprovals < 1 || maxConcurrentQueries < 1) {
                throw new IllegalArgumentException("payment.pg.max-concurrent-* 는 1 이상이어야 한다");
            }
            if (approvalMaxWait.isNegative()) {
                throw new IllegalArgumentException("payment.pg.approval-max-wait는 0 이상이어야 한다");
            }
        }
    }

    /** 최근 windowSize건 중 실패율이 임계치 이상이면 openDuration 동안 열린다. */
    public record Breaker(
            @DefaultValue("20") int windowSize,
            @DefaultValue("20") int minimumCalls,
            @DefaultValue("50") float failureRateThreshold,
            @DefaultValue("10s") Duration openDuration) {}

    /**
     * 거래 조회 일정. n번째 조회 간격은 {@code initialDelay * 2^n}이고 maxInterval을 넘지 않는다(1, 2, 4, 8, 16초 뒤 30초). maxDuration이
     * 지나면 조회를 멈추고 UNKNOWN으로 남긴다.
     */
    public record Inquiry(
            @DefaultValue("1s") Duration initialDelay,
            @DefaultValue("30s") Duration maxInterval,
            @DefaultValue("10m") Duration maxDuration,
            @DefaultValue("1s") Duration pollInterval,
            @DefaultValue("50") int batchSize,
            @DefaultValue("30s") Duration lease,
            @DefaultValue("1m") Duration requestedStaleAfter,
            @DefaultValue("500ms") Duration rejectedRetryDelay) {

        /** Bulkhead 거절 뒤 다시 시도하기까지의 간격. 같이 거절된 건이 한꺼번에 돌아오지 않게 1~2배로 흩뜨린다. */
        public Duration jitteredRejectedRetryDelay() {
            return rejectedRetryDelay.plusMillis(
                    ThreadLocalRandom.current().nextLong(rejectedRetryDelay.toMillis() + 1));
        }

        public Duration delayAfter(int attempts) {
            long factor = 1L << Math.min(attempts, 20);
            Duration delay = initialDelay.multipliedBy(factor);
            return delay.compareTo(maxInterval) > 0 ? maxInterval : delay;
        }
    }

    /**
     * PG 취소(환불) 재시도. n번째 재시도 간격은 {@code initialDelay * 2^n}이고 maxInterval을 넘지 않는다. maxAttempts번 시도해도 취소하지 못하면 CANCEL_FAILED로 두고
     * 수동 처리한다. lease는 처리 중인 건을 다른 인스턴스나 스케줄러가 다시 집지 않게 하는 시간이며, 한 번의 시도(거래 조회 + 취소, 각각 PG read timeout 이내)보다 길어야 한다.
     */
    public record Cancel(
            @DefaultValue("1s") Duration initialDelay,
            @DefaultValue("30s") Duration maxInterval,
            @DefaultValue("8") int maxAttempts,
            @DefaultValue("1s") Duration pollInterval,
            @DefaultValue("50") int batchSize,
            @DefaultValue("30s") Duration lease) {

        public Duration delayAfter(int attempts) {
            long factor = 1L << Math.min(Math.max(attempts - 1, 0), 20);
            Duration delay = initialDelay.multipliedBy(factor);
            return delay.compareTo(maxInterval) > 0 ? maxInterval : delay;
        }
    }
}
