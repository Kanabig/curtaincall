package io.github.kanabig.curtaincall.searchassist.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code search-assist.*} 설정. mode가 auto면 API 키가 있을 때 LLM, 없으면 Mock을 쓴다. mock은 키가 있어도 Mock으로 고정하고, llm은 키가 없으면 기동할 때
 * 실패한다.
 */
@ConfigurationProperties("search-assist")
public record SearchAssistProperties(
        @DefaultValue("auto") Mode mode,
        @DefaultValue Llm llm,
        @DefaultValue Breaker breaker) {

    public enum Mode {
        AUTO,
        MOCK,
        LLM
    }

    public record Llm(
            @DefaultValue("") String apiKey,
            @DefaultValue("") String baseUrl,
            @DefaultValue("claude-haiku-5-5") String model,
            @DefaultValue("low") String effort,
            @DefaultValue("2s") Duration timeout,
            @DefaultValue("1000") int dailyLimit) {

        public boolean hasKey() {
            return apiKey != null && !apiKey.isBlank();
        }
    }

    /** 최근 windowSize건 중 실패율이 임계치 이상이면 openDuration 동안 LLM을 부르지 않는다. */
    public record Breaker(
            @DefaultValue("20") int windowSize,
            @DefaultValue("10") int minimumCalls,
            @DefaultValue("50") float failureRateThreshold,
            @DefaultValue("30s") Duration openDuration) {}
}
