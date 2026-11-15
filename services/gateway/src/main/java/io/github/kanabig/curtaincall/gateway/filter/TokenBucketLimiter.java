package io.github.kanabig.curtaincall.gateway.filter;

import io.github.kanabig.curtaincall.gateway.config.RateLimitProperties;
import io.github.kanabig.curtaincall.gateway.config.RateLimitProperties.Bucket;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Redis Lua 토큰 버킷. Redis 호출이 실패하거나 제한 시간을 넘기면 통과시킨다(fail-open).
 *
 * <p>실패하면 서킷을 열어 circuit-open 동안은 Redis를 부르지 않고 바로 통과시킨다. IP·계정 필터가 이 상태를 함께 쓰므로 장애 중에도 요청이 타임아웃을
 * 기다리지 않는다. 시간이 지나면 요청 하나만 Redis를 불러 보고(half-open) 성공하면 닫는다. 건너뛴 요청도 gateway.ratelimit.failopen에
 * 센다.
 */
@Component
public class TokenBucketLimiter {

    private static final Logger log = LoggerFactory.getLogger(TokenBucketLimiter.class);

    @SuppressWarnings("rawtypes")
    private static final DefaultRedisScript<List> SCRIPT = script();

    private final ReactiveStringRedisTemplate redis;
    private final RateLimitProperties properties;
    private final MeterRegistry meters;

    /** 이 시각(System.nanoTime)까지는 Redis를 부르지 않는다. */
    private final AtomicLong skipUntil = new AtomicLong(System.nanoTime() - 1);

    private final AtomicBoolean open = new AtomicBoolean();
    private final AtomicBoolean probing = new AtomicBoolean();

    public TokenBucketLimiter(ReactiveStringRedisTemplate redis, RateLimitProperties properties, MeterRegistry meters) {
        this.redis = redis;
        this.properties = properties;
        this.meters = meters;
    }

    /**
     * @param allowed 요청을 보내도 되는가
     * @param retryAfterSeconds 거절일 때 다시 시도할 때까지 초(올림, 1 이상)
     */
    public record Decision(boolean allowed, long retryAfterSeconds) {
        static final Decision PASS = new Decision(true, 0);
    }

    public Mono<Decision> acquire(String scope, String id, Bucket bucket) {
        boolean probe = false;
        if (open.get()) {
            if (System.nanoTime() - skipUntil.get() < 0 || !probing.compareAndSet(false, true)) {
                return skipped(scope);
            }
            probe = true;
        }
        boolean isProbe = probe;
        String key = "gateway:rl:" + scope + ":" + id;
        List<String> args = List.of(Double.toString(bucket.ratePerSecond()), Integer.toString(bucket.burst()));
        return redis.execute(SCRIPT, List.of(key), args)
                .next()
                .map(TokenBucketLimiter::decode)
                .timeout(properties.timeout())
                .switchIfEmpty(Mono.error(new IllegalStateException("빈 응답")))
                .doOnNext(decision -> onSuccess())
                .onErrorResume(ex -> failOpen(scope, ex))
                .doFinally(signal -> {
                    if (isProbe) {
                        probing.set(false);
                    }
                });
    }

    private Mono<Decision> skipped(String scope) {
        meters.counter("gateway.ratelimit.failopen", "scope", scope).increment();
        return Mono.just(Decision.PASS);
    }

    private void onSuccess() {
        if (open.compareAndSet(true, false)) {
            log.info("속도 제한 Redis 호출이 복구되어 한도를 다시 적용한다");
        }
    }

    private Mono<Decision> failOpen(String scope, Throwable ex) {
        meters.counter("gateway.ratelimit.failopen", "scope", scope).increment();
        skipUntil.set(System.nanoTime() + properties.circuitOpen().toNanos());
        // 처음 열릴 때와 재시도(half-open)가 실패할 때만 경고한다. 재시도는 circuit-open 간격이라 로그가 쌓이지 않는다
        if (open.getAndSet(true) && !probing.get()) {
            log.debug("속도 제한 Redis 호출 실패: scope={}, {}", scope, ex.toString());
        } else {
            log.warn(
                    "속도 제한 Redis 호출 실패, {} 동안 제한 없이 통과시킨다: scope={}, {}",
                    properties.circuitOpen(),
                    scope,
                    ex.toString());
        }
        return Mono.just(Decision.PASS);
    }

    @SuppressWarnings("rawtypes")
    private static Decision decode(List result) {
        boolean allowed = ((Number) result.get(0)).longValue() == 1L;
        if (allowed) {
            return Decision.PASS;
        }
        long retryMs = ((Number) result.get(1)).longValue();
        return new Decision(false, Math.max(1, (retryMs + 999) / 1000));
    }

    @SuppressWarnings("rawtypes")
    private static DefaultRedisScript<List> script() {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("scripts/token-bucket.lua"));
        script.setResultType(List.class);
        return script;
    }
}
