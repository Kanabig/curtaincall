package io.github.kanabig.curtaincall.gateway.filter;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * gateway가 Redis를 부르는 fail-open 경로(속도 제한, 입장 활동)의 서킷 구현. 인스턴스는 경로마다 하나라 한 경로의 실패가 다른 경로를 끄지 않는다.
 * 상태 게이지 gateway.redis.circuit.state 값은 0 닫힘, 1 열림, 2 반열림(프로브 진행 중).
 *
 * <p>닫힘 → 호출 하나가 실패하면 열림. 열림 → {@code openFor}가 지나면 요청 하나만 프로브(half-open)로 Redis를 부르고, 그 호출이 성공해야만 닫힌다. 열려 있는 동안과
 * 프로브가 나가 있는 동안 나머지 요청은 Redis를 부르지 않고 fail-open한다. 닫혀 있을 때 나간 호출이 서킷이 열린 뒤에 늦게 끝나면 성공이든 실패든 상태를 바꾸지 않는다.
 * 부하로 응답이 늦어질 때 늦은 성공이 서킷을 바로 닫아 열림·닫힘이 반복되던 문제(#159)를 막는다.
 */
class RedisCircuit {

    private static final Logger log = LoggerFactory.getLogger(RedisCircuit.class);

    private static final int CLOSED = 0;
    private static final int OPEN = 1;
    private static final int HALF_OPEN = 2;
    private static final long PROBE_WARN_INTERVAL_NANOS = Duration.ofSeconds(10).toNanos();

    private final Duration openFor;
    private final LongSupplier clock;
    private final String openLog;
    private final String closeLog;
    private final Counter opened;
    private final Timer successTimer;
    private final Timer failureTimer;

    private final AtomicInteger state = new AtomicInteger(CLOSED);
    /** OPEN에서 이 시각(clock 기준)이 지나야 프로브를 보낸다. */
    private final AtomicLong probeAt = new AtomicLong();
    /** 프로브 실패 WARN을 마지막으로 남긴 시각. 장애가 길어져도 로그가 10초에 한 줄만 쌓이게 한다. */
    private final AtomicLong lastProbeWarnAt = new AtomicLong();

    private volatile boolean probeWarned;

    RedisCircuit(String name, Duration openFor, MeterRegistry meters, String openLog, String closeLog) {
        this(name, openFor, meters, System::nanoTime, openLog, closeLog);
    }

    RedisCircuit(
            String name, Duration openFor, MeterRegistry meters, LongSupplier clock, String openLog, String closeLog) {
        this.openFor = openFor;
        this.clock = clock;
        this.openLog = openLog;
        this.closeLog = closeLog;
        this.opened = Counter.builder("gateway.redis.circuit.opened")
                .tag("name", name)
                .register(meters);
        this.successTimer = timer(meters, name, "success");
        this.failureTimer = timer(meters, name, "failure");
        meters.gauge("gateway.redis.circuit.state", List.of(Tag.of("name", name)), state);
    }

    private static Timer timer(MeterRegistry meters, String name, String outcome) {
        return Timer.builder("gateway.redis.call")
                .tag("name", name)
                .tag("outcome", outcome)
                .register(meters);
    }

    /**
     * 서킷을 거쳐 Redis를 부른다. 건너뛰거나 실패·시간 초과·빈 응답이면 {@code fallback}을 주고 {@code onFailOpen}을 부른다.
     */
    <T> Mono<T> call(Supplier<Mono<T>> op, Duration timeout, T fallback, Runnable onFailOpen) {
        return Mono.defer(() -> {
            boolean probe;
            int s = state.get();
            if (s == CLOSED) {
                probe = false;
            } else if (s == OPEN && clock.getAsLong() - probeAt.get() >= 0 && state.compareAndSet(OPEN, HALF_OPEN)) {
                probe = true;
            } else {
                onFailOpen.run();
                return Mono.just(fallback);
            }
            long start = clock.getAsLong();
            return Mono.defer(op)
                    .timeout(timeout)
                    .switchIfEmpty(Mono.error(new IllegalStateException("빈 응답")))
                    .doOnNext(r -> {
                        record(start, true);
                        if (probe) {
                            close();
                        }
                    })
                    .onErrorResume(ex -> {
                        record(start, false);
                        onFailOpen.run();
                        failed(probe, String.valueOf(ex));
                        return Mono.just(fallback);
                    })
                    .doOnCancel(() -> {
                        // 프로브가 결과 없이 취소되면 HALF_OPEN에 갇히지 않게 실패로 본다
                        if (probe) {
                            failed(true, "프로브 취소");
                        }
                    });
        });
    }

    boolean isOpen() {
        return state.get() != CLOSED;
    }

    private void record(long start, boolean success) {
        (success ? successTimer : failureTimer).record(Duration.ofNanos(clock.getAsLong() - start));
    }

    private void close() {
        if (state.compareAndSet(HALF_OPEN, CLOSED)) {
            log.info(closeLog);
        }
    }

    private void failed(boolean probe, String cause) {
        if (probe) {
            probeAt.set(clock.getAsLong() + openFor.toNanos());
            if (state.compareAndSet(HALF_OPEN, OPEN) && shouldWarnProbe()) {
                log.warn("{} 프로브 실패, {} 더 통과시킨다: {}", openLog, openFor, cause);
            }
            return;
        }
        // 닫혀 있을 때만 연다. 이미 열린 뒤 늦게 도착한 실패는 열림 시간을 늘리지 않는다
        if (state.get() == CLOSED) {
            probeAt.set(clock.getAsLong() + openFor.toNanos());
            if (state.compareAndSet(CLOSED, OPEN)) {
                opened.increment();
                log.warn("{}, {} 동안 Redis를 부르지 않고 통과시킨다: {}", openLog, openFor, cause);
            }
        }
    }

    private boolean shouldWarnProbe() {
        long now = clock.getAsLong();
        synchronized (lastProbeWarnAt) {
            if (probeWarned && now - lastProbeWarnAt.get() < PROBE_WARN_INTERVAL_NANOS) {
                return false;
            }
            probeWarned = true;
            lastProbeWarnAt.set(now);
            return true;
        }
    }
}
