package io.github.kanabig.curtaincall.gateway.config;

import io.github.kanabig.curtaincall.gateway.filter.ClientIp;
import io.github.kanabig.curtaincall.gateway.filter.ProblemWriter;
import io.github.kanabig.curtaincall.gateway.filter.RateLimitFilter;
import io.github.kanabig.curtaincall.gateway.filter.TokenBucketLimiter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/** [GW-2] IP 한도는 인증보다 먼저(401 연타도 막는다), 계정 한도는 인증 필터가 X-User-Id를 넣은 뒤에 건다. */
@Configuration
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimitConfig {

    static final String USER_ID_HEADER = "X-User-Id";
    private static final PathPattern QUEUE_POLL =
            PathPatternParser.defaultInstance.parse("/api/v1/queue/sessions/{sessionId}/entries/me");

    @Bean
    RateLimitFilter ipRateLimitFilter(
            RateLimitProperties properties, TokenBucketLimiter limiter, ProblemWriter problems, MeterRegistry meters) {
        return new RateLimitFilter(
                "ip",
                Ordered.HIGHEST_PRECEDENCE + 5,
                properties.ip(),
                exchange -> isQueuePoll(exchange)
                        ? null
                        : ClientIp.resolve(exchange.getRequest(), properties.trustForwardedFor()),
                limiter,
                problems,
                meters);
    }

    /** 폴링 전용 IP 한도. 키가 "ip:poll:<IP>"라 일반 IP 버킷과 섞이지 않는다. */
    @Bean
    RateLimitFilter ipPollRateLimitFilter(
            RateLimitProperties properties, TokenBucketLimiter limiter, ProblemWriter problems, MeterRegistry meters) {
        return new RateLimitFilter(
                "ip:poll",
                Ordered.HIGHEST_PRECEDENCE + 6,
                properties.ipPoll(),
                exchange -> isQueuePoll(exchange)
                        ? ClientIp.resolve(exchange.getRequest(), properties.trustForwardedFor())
                        : null,
                limiter,
                problems,
                meters);
    }

    /** GET /api/v1/queue/sessions/{id}/entries/me. 화면이 주기적으로 부르는 순번 조회다. */
    private static boolean isQueuePoll(ServerWebExchange exchange) {
        return HttpMethod.GET.equals(exchange.getRequest().getMethod())
                && QUEUE_POLL.matches(exchange.getRequest().getPath().pathWithinApplication());
    }

    @Bean
    RateLimitFilter userRateLimitFilter(
            RateLimitProperties properties, TokenBucketLimiter limiter, ProblemWriter problems, MeterRegistry meters) {
        return new RateLimitFilter(
                "user",
                Ordered.HIGHEST_PRECEDENCE + 20,
                properties.user(),
                exchange -> exchange.getRequest().getHeaders().getFirst(USER_ID_HEADER),
                limiter,
                problems,
                meters);
    }
}
