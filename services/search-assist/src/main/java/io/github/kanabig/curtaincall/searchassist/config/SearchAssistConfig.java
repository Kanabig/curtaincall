package io.github.kanabig.curtaincall.searchassist.config;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.ZoneId;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(SearchAssistProperties.class)
public class SearchAssistConfig {

    public static final ZoneId KST = ZoneId.of("Asia/Seoul");

    /** "오늘"과 하루 호출 한도의 기준. KST. */
    @Bean
    @ConditionalOnMissingBean
    Clock clock() {
        return Clock.system(KST);
    }

    @Bean
    CircuitBreaker llmCircuitBreaker(SearchAssistProperties properties, MeterRegistry meterRegistry) {
        SearchAssistProperties.Breaker breaker = properties.breaker();
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(breaker.windowSize())
                .minimumNumberOfCalls(breaker.minimumCalls())
                .failureRateThreshold(breaker.failureRateThreshold())
                .waitDurationInOpenState(breaker.openDuration())
                .permittedNumberOfCallsInHalfOpenState(3)
                .build();
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(config);
        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(registry).bindTo(meterRegistry);
        return registry.circuitBreaker("llm");
    }
}
