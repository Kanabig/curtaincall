package io.github.kanabig.curtaincall.searchassist.service;

import io.github.kanabig.curtaincall.common.web.metrics.MetricNames;
import io.github.kanabig.curtaincall.searchassist.client.ConditionExtractor;
import io.github.kanabig.curtaincall.searchassist.client.ExtractionException;
import io.github.kanabig.curtaincall.searchassist.client.LlmConditionExtractor;
import io.github.kanabig.curtaincall.searchassist.client.MockConditionExtractor;
import io.github.kanabig.curtaincall.searchassist.client.RawConditions;
import io.github.kanabig.curtaincall.searchassist.config.SearchAssistProperties;
import io.github.kanabig.curtaincall.searchassist.dto.AssistResponse;
import io.github.kanabig.curtaincall.searchassist.dto.SearchConditions;
import io.github.kanabig.curtaincall.searchassist.dto.Source;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.HexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class SearchAssistService {

    private static final Logger log = LoggerFactory.getLogger(SearchAssistService.class);

    private final ConditionExtractor llm;
    private final MockConditionExtractor mock;
    private final ConditionValidator validator;
    private final CircuitBreaker breaker;
    private final DailyQuota quota;
    private final Clock clock;
    private final MeterRegistry meters;
    private final Timer llmLatency;
    private final String provider;

    public SearchAssistService(
            LlmConditionExtractor llm,
            MockConditionExtractor mock,
            ConditionValidator validator,
            CircuitBreaker llmCircuitBreaker,
            SearchAssistProperties properties,
            Clock clock,
            MeterRegistry meters) {
        this.llm = llm.enabled() ? llm : null;
        this.provider = properties.llm().provider().name();
        this.mock = mock;
        this.validator = validator;
        this.breaker = llmCircuitBreaker;
        this.quota = new DailyQuota(clock, properties.llm().dailyLimit());
        this.clock = clock;
        this.meters = meters;
        this.llmLatency = Timer.builder(MetricNames.SEARCH_ASSIST_LLM_LATENCY)
                .publishPercentileHistogram()
                .register(meters);
    }

    public AssistResponse assist(String query) {
        LocalDate today = LocalDate.now(clock);
        AssistResponse response = resolve(query, today);
        // 입력 원문에는 개인정보가 섞일 수 있어 길이와 해시만 남긴다
        log.info("assist source={} length={} hash={}", response.source(), query.length(), hash(query));
        return response;
    }

    private AssistResponse resolve(String query, LocalDate today) {
        if (llm != null) {
            if (quota.tryAcquire()) {
                return viaLlm(query, today);
            }
            llmCall("quota_exceeded");
        }
        return viaMock(query, today);
    }

    private AssistResponse viaLlm(String query, LocalDate today) {
        RawConditions raw;
        Timer.Sample sample = Timer.start(meters);
        try {
            raw = breaker.executeSupplier(() -> llm.extract(query, today));
        } catch (CallNotPermittedException e) {
            llmCall("rejected");
            return fallback(query);
        } catch (ExtractionException e) {
            llmCall(e.isRateLimited() ? "rate_limited" : e.isTimeout() ? "timeout" : "error");
            // 원인 구분용. 입력 원문과 키는 남기지 않는다
            log.warn(
                    "llm call failed provider={} reason={} status={} cause={} length={} hash={}",
                    provider,
                    e.reason(),
                    e.status(),
                    e.getCause() == null ? "-" : e.getCause().getClass().getSimpleName(),
                    query.length(),
                    hash(query));
            return fallback(query);
        } catch (RuntimeException e) {
            llmCall("error");
            log.warn(
                    "llm call failed provider={} reason=UNEXPECTED cause={} length={} hash={}",
                    provider,
                    e.getClass().getSimpleName(),
                    query.length(),
                    hash(query));
            return fallback(query);
        } finally {
            sample.stop(llmLatency);
        }
        try {
            SearchConditions conditions = validator.validate(raw, today);
            llmCall("success");
            return new AssistResponse(conditions, Source.LLM);
        } catch (InvalidConditionsException e) {
            llmCall("invalid");
            validationFailed(e);
            return fallback(query);
        }
    }

    private AssistResponse viaMock(String query, LocalDate today) {
        try {
            return new AssistResponse(validator.validate(mock.extract(query, today), today), Source.MOCK);
        } catch (InvalidConditionsException e) {
            validationFailed(e);
        } catch (RuntimeException e) {
            log.warn("mock extraction failed: {}", e.getClass().getSimpleName());
        }
        return fallback(query);
    }

    private AssistResponse fallback(String query) {
        String keyword = ConditionValidator.cleanKeyword(query, ConditionValidator.MAX_KEYWORD_LENGTH);
        return new AssistResponse(SearchConditions.keywordOnly(keyword), Source.FALLBACK);
    }

    private void llmCall(String result) {
        meters.counter(MetricNames.SEARCH_ASSIST_LLM_CALLS, MetricNames.TAG_RESULT, result)
                .increment();
    }

    private void validationFailed(InvalidConditionsException e) {
        meters.counter(MetricNames.SEARCH_ASSIST_VALIDATION_FAILURES, MetricNames.TAG_FIELD, e.field())
                .increment();
    }

    private static String hash(String query) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(query.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 4);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
