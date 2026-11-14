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

/** [GW-2] IP 한도는 인증보다 먼저(401 연타도 막는다), 계정 한도는 인증 필터가 X-User-Id를 넣은 뒤에 건다. */
@Configuration
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimitConfig {

    static final String USER_ID_HEADER = "X-User-Id";

    @Bean
    RateLimitFilter ipRateLimitFilter(
            RateLimitProperties properties, TokenBucketLimiter limiter, ProblemWriter problems, MeterRegistry meters) {
        return new RateLimitFilter(
                "ip",
                Ordered.HIGHEST_PRECEDENCE + 5,
                properties.ip(),
                exchange -> ClientIp.resolve(exchange.getRequest(), properties.trustForwardedFor()),
                limiter,
                problems,
                meters);
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
