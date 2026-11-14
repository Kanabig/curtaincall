package io.github.kanabig.curtaincall.gateway.filter;

import io.github.kanabig.curtaincall.gateway.config.RateLimitProperties;
import io.github.kanabig.curtaincall.gateway.config.RateLimitProperties.Bucket;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
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
 * <p>fail-open 횟수는 gateway.ratelimit.failopen 카운터로 남기고, 경고 로그는 요청마다 찍지 않도록 10초에 한 번만 찍는다.
 */
@Component
public class TokenBucketLimiter {

    private static final Logger log = LoggerFactory.getLogger(TokenBucketLimiter.class);
    private static final long WARN_INTERVAL_NANOS = 10_000_000_000L;

    @SuppressWarnings("rawtypes")
    private static final DefaultRedisScript<List> SCRIPT = script();

    private final ReactiveStringRedisTemplate redis;
    private final RateLimitProperties properties;
    private final MeterRegistry meters;
    private final AtomicLong lastWarn = new AtomicLong(System.nanoTime() - WARN_INTERVAL_NANOS);

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
        String key = "gateway:rl:" + scope + ":" + id;
        List<String> args = List.of(Double.toString(bucket.ratePerSecond()), Integer.toString(bucket.burst()));
        return redis.execute(SCRIPT, List.of(key), args)
                .next()
                .map(TokenBucketLimiter::decode)
                .timeout(properties.timeout())
                .switchIfEmpty(Mono.error(new IllegalStateException("빈 응답")))
                .onErrorResume(ex -> failOpen(scope, ex));
    }

    private Mono<Decision> failOpen(String scope, Throwable ex) {
        meters.counter("gateway.ratelimit.failopen", "scope", scope).increment();
        long now = System.nanoTime();
        long last = lastWarn.get();
        if (now - last >= WARN_INTERVAL_NANOS && lastWarn.compareAndSet(last, now)) {
            log.warn("속도 제한 Redis 호출 실패, 제한 없이 통과시킨다: scope={}, {}", scope, ex.toString());
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
