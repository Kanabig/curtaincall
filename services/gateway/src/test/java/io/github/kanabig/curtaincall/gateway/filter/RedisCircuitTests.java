package io.github.kanabig.curtaincall.gateway.filter;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/** [GW-2] 서킷은 half-open 프로브 성공으로만 닫힌다. 시계와 응답 시점을 직접 제어해 결정적으로 검증한다(#159). */
class RedisCircuitTests {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final AtomicLong now = new AtomicLong(1_000_000_000L);
    private final AtomicInteger failOpen = new AtomicInteger();
    private final RedisCircuit circuit =
            new RedisCircuit("test", Duration.ofSeconds(2), new SimpleMeterRegistry(), now::get, "호출 실패", "호출 복구");

    private Mono<String> call(Mono<String> op) {
        return circuit.call(() -> op, TIMEOUT, "FALLBACK", failOpen::incrementAndGet);
    }

    private void advance(Duration d) {
        now.addAndGet(d.toNanos());
    }

    @Test
    @DisplayName("열리기 전에 나간 호출이 늦게 성공해도 서킷은 닫히지 않는다")
    void lateSuccessDoesNotClose() {
        Sinks.One<String> late = Sinks.one();
        var lateResult = new String[1];
        call(late.asMono()).subscribe(r -> lateResult[0] = r);

        // 같은 시각에 나간 다른 호출이 실패해 서킷이 열린다
        assertThat(call(Mono.error(new IllegalStateException("timeout"))).block())
                .isEqualTo("FALLBACK");
        assertThat(circuit.isOpen()).isTrue();

        late.tryEmitValue("OK");
        assertThat(lateResult[0]).isEqualTo("OK");
        assertThat(circuit.isOpen()).isTrue();

        // 열려 있는 동안은 Redis를 부르지 않는다
        var called = new AtomicInteger();
        assertThat(call(Mono.fromSupplier(() -> {
                            called.incrementAndGet();
                            return "OK";
                        }))
                        .block())
                .isEqualTo("FALLBACK");
        assertThat(called).hasValue(0);
    }

    @Test
    @DisplayName("열린 뒤 늦게 도착한 실패는 열림 시간을 늘리지 않는다")
    void lateFailureDoesNotExtendOpen() {
        Sinks.One<String> late = Sinks.one();
        call(late.asMono()).subscribe();
        call(Mono.error(new IllegalStateException("timeout"))).block();

        advance(Duration.ofSeconds(1));
        late.tryEmitError(new IllegalStateException("late timeout"));
        advance(Duration.ofSeconds(1));

        // 처음 열린 시각 기준 2초가 지났으므로 프로브가 나간다
        assertThat(call(Mono.just("OK")).block()).isEqualTo("OK");
        assertThat(circuit.isOpen()).isFalse();
    }

    @Test
    @DisplayName("열림 시간이 지나면 요청 하나만 프로브로 나가고, 프로브가 나가 있는 동안 나머지는 건너뛴다")
    void singleProbe() {
        call(Mono.error(new IllegalStateException("timeout"))).block();
        advance(Duration.ofSeconds(2));

        Sinks.One<String> probe = Sinks.one();
        var probeResult = new String[1];
        call(probe.asMono()).subscribe(r -> probeResult[0] = r);

        var called = new AtomicInteger();
        for (int i = 0; i < 3; i++) {
            assertThat(call(Mono.fromSupplier(() -> {
                                called.incrementAndGet();
                                return "OK";
                            }))
                            .block())
                    .isEqualTo("FALLBACK");
        }
        assertThat(called).hasValue(0);

        probe.tryEmitValue("OK");
        assertThat(probeResult[0]).isEqualTo("OK");
        assertThat(circuit.isOpen()).isFalse();
        assertThat(call(Mono.just("OK")).block()).isEqualTo("OK");
    }

    @Test
    @DisplayName("프로브가 실패하면 열림 시간을 다시 채운다")
    void probeFailureReopens() {
        call(Mono.error(new IllegalStateException("timeout"))).block();
        advance(Duration.ofSeconds(2));
        assertThat(call(Mono.error(new IllegalStateException("still down"))).block())
                .isEqualTo("FALLBACK");

        advance(Duration.ofSeconds(1));
        assertThat(call(Mono.just("OK")).block()).isEqualTo("FALLBACK");
        assertThat(circuit.isOpen()).isTrue();

        advance(Duration.ofSeconds(1));
        assertThat(call(Mono.just("OK")).block()).isEqualTo("OK");
        assertThat(circuit.isOpen()).isFalse();
    }

    @Test
    @DisplayName("프로브가 결과 없이 취소돼도 서킷이 반열림에 갇히지 않는다")
    void cancelledProbeReopens() {
        call(Mono.error(new IllegalStateException("timeout"))).block();
        advance(Duration.ofSeconds(2));

        call(Sinks.<String>one().asMono()).subscribe().dispose();
        advance(Duration.ofSeconds(2));

        assertThat(call(Mono.just("OK")).block()).isEqualTo("OK");
        assertThat(circuit.isOpen()).isFalse();
    }

    @Test
    @DisplayName("빈 응답은 실패로 본다")
    void emptyIsFailure() {
        assertThat(call(Mono.empty()).block()).isEqualTo("FALLBACK");
        assertThat(circuit.isOpen()).isTrue();
    }

    @Test
    @DisplayName("구독하지 않은 호출은 프로브 자리를 차지하지 않는다")
    void unsubscribedCallDoesNotTakeProbe() {
        call(Mono.error(new IllegalStateException("timeout"))).block();
        advance(Duration.ofSeconds(2));

        call(Mono.just("OK")); // 구독하지 않음
        advance(Duration.ofSeconds(60));

        assertThat(call(Mono.just("OK")).block()).isEqualTo("OK");
        assertThat(circuit.isOpen()).isFalse();
    }

    @Test
    @DisplayName("프로브 실패 WARN은 10초에 한 번만 남긴다")
    void probeFailureWarnIsThrottled() {
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        var logger = (Logger) LoggerFactory.getLogger(RedisCircuit.class);
        logger.addAppender(appender);
        try {
            call(Mono.error(new IllegalStateException("timeout"))).block();
            // 열림 WARN 1건
            for (int i = 0; i < 4; i++) {
                advance(Duration.ofSeconds(2));
                call(Mono.error(new IllegalStateException("still down"))).block();
            }
            // 8초 동안 프로브가 4번 실패했지만 프로브 실패 WARN은 첫 번째 하나뿐이다
            assertThat(warns(appender)).hasSize(2);

            advance(Duration.ofSeconds(10));
            call(Mono.error(new IllegalStateException("still down"))).block();
            assertThat(warns(appender)).hasSize(3);
        } finally {
            logger.detachAppender(appender);
        }
    }

    private static java.util.List<ILoggingEvent> warns(ListAppender<ILoggingEvent> appender) {
        return appender.list.stream().filter(e -> e.getLevel() == Level.WARN).toList();
    }
}
