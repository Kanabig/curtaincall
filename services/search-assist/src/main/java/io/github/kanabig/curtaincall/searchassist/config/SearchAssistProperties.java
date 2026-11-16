package io.github.kanabig.curtaincall.searchassist.config;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code search-assist.*} 설정. {@code llm.provider}로 제공자(claude, gemini, groq, exaone)를 고르고, 선택한 제공자의 키가 없으면 Mock이다. mode가 auto면 API 키가 있을 때 LLM, 없으면 Mock을 쓴다. mock은 키가 있어도 Mock으로 고정하고, llm은 키가 없으면 기동할 때
 * 실패한다.
 */
@ConfigurationProperties("search-assist")
public record SearchAssistProperties(
        @DefaultValue("auto") Mode mode,
        @DefaultValue Llm llm,
        @DefaultValue Breaker breaker,
        Map<String, Compatible> providers) {

    /** 제공자별 설정. 없으면 기본값(키 없음)이라 Mock으로 동작한다. */
    public Compatible provider(ProviderType type) {
        Compatible found = providers == null ? null : providers.get(type.name().toLowerCase(Locale.ROOT));
        return found != null
                ? found
                : new Compatible("", "", "/chat/completions", "", ResponseFormat.NONE, "", 1024, Duration.ZERO);
    }

    public enum ProviderType {
        CLAUDE,
        GEMINI,
        GROQ,
        EXAONE
    }

    /** 응답 형식. json_schema가 안 되는 제공자는 json_object나 프롬프트 지시로 받는다. */
    public enum ResponseFormat {
        JSON_SCHEMA,
        JSON_OBJECT,
        NONE
    }

    /**
     * OpenAI 호환 제공자 하나. reasoningEffort가 비면 보내지 않는다. maxTokens는 추론 토큰을 포함하는 제공자가 있어 넉넉히 잡는다. minInterval은 서비스가 아니라 평가 실행기가
     * 무료 한도를 지키려고 쓴다.
     */
    public record Compatible(
            @DefaultValue("") String apiKey,
            @DefaultValue("") String baseUrl,
            @DefaultValue("/chat/completions") String path,
            @DefaultValue("") String model,
            @DefaultValue("NONE") ResponseFormat responseFormat,
            @DefaultValue("") String reasoningEffort,
            @DefaultValue("1024") int maxTokens,
            @DefaultValue("0s") Duration minInterval) {

        public boolean hasKey() {
            return apiKey != null && !apiKey.isBlank();
        }
    }

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
            @DefaultValue("1000") int dailyLimit,
            @DefaultValue("gemini") ProviderType provider) {

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
