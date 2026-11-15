package io.github.kanabig.curtaincall.gateway.filter;

import io.github.kanabig.curtaincall.gateway.config.RateLimitProperties.Bucket;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.function.Function;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * [GW-2] 요청 속도 제한. 키를 구하지 못하면(로그인하지 않은 요청의 계정 한도 등) 이 한도는 건너뛴다.
 *
 * <p>같은 클래스를 IP용과 계정용으로 두 번 등록한다. 계정용은 인증 필터가 X-User-Id를 넣은 뒤에 돌아야 한다.
 */
public class RateLimitFilter implements GlobalFilter, Ordered {

    private final String scope;
    private final int order;
    private final Bucket bucket;
    private final Function<ServerWebExchange, String> keyResolver;
    private final TokenBucketLimiter limiter;
    private final ProblemWriter problems;
    private final MeterRegistry meters;

    public RateLimitFilter(
            String scope,
            int order,
            Bucket bucket,
            Function<ServerWebExchange, String> keyResolver,
            TokenBucketLimiter limiter,
            ProblemWriter problems,
            MeterRegistry meters) {
        this.scope = scope;
        this.order = order;
        this.bucket = bucket;
        this.keyResolver = keyResolver;
        this.limiter = limiter;
        this.problems = problems;
        this.meters = meters;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String key = keyResolver.apply(exchange);
        if (key == null) {
            return chain.filter(exchange);
        }
        return limiter.acquire(scope, key, bucket).flatMap(decision -> {
            if (decision.allowed()) {
                return chain.filter(exchange);
            }
            meters.counter("gateway.ratelimit.rejected", "scope", scope).increment();
            exchange.getResponse()
                    .getHeaders()
                    .set(HttpHeaders.RETRY_AFTER, Long.toString(decision.retryAfterSeconds()));
            return problems.write(
                    exchange, HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_REQUESTS", "요청이 너무 많다. 잠시 뒤 다시 시도한다");
        });
    }

    @Override
    public int getOrder() {
        return order;
    }
}
