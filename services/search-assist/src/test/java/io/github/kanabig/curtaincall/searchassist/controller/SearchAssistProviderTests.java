package io.github.kanabig.curtaincall.searchassist.controller;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.kanabig.curtaincall.searchassist.client.FakeChatCompletionsServer;
import io.github.kanabig.curtaincall.searchassist.dto.AssistResponse;
import io.github.kanabig.curtaincall.searchassist.dto.Source;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** SRC-1, SRC-2. llm.provider=groq로 OpenAI 호환 추출기가 공통 검증·FALLBACK·지표 경로를 그대로 타는지 본다. 제공자는 로컬 가짜 서버다. */
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SearchAssistProviderTests {

    static final FakeChatCompletionsServer FAKE = new FakeChatCompletionsServer();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("search-assist.llm.provider", () -> "groq");
        registry.add("search-assist.llm.timeout", () -> "500ms");
        registry.add("search-assist.providers.groq.api-key", () -> "test-key");
        registry.add("search-assist.providers.groq.base-url", FAKE::baseUrl);
        registry.add("search-assist.breaker.minimum-calls", () -> "1000");
    }

    @AfterAll
    static void stop() {
        FAKE.close();
    }

    @Autowired
    TestRestTemplate rest;

    @Autowired
    MeterRegistry meters;

    @BeforeEach
    void reset() {
        FAKE.reset();
    }

    private AssistResponse assist(String query) {
        return rest.postForEntity("/api/v1/search/assist", Map.of("query", query), AssistResponse.class)
                .getBody();
    }

    private double calls(String result) {
        var counter =
                meters.find("search.assist.llm.calls").tag("result", result).counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    @DisplayName("선택한 제공자 응답은 검증을 거쳐 LLM 조건이 되고, 서버 설정의 모델이 나간다")
    void success() {
        FAKE.replyContent("```json\n{\"region\":\"서울특별시\",\"dateFrom\":null,\"dateTo\":null,\"genre\":\"뮤지컬\","
                + "\"maxPrice\":null,\"keyword\":null}\n```");
        double before = calls("success");

        AssistResponse response = assist("서울 뮤지컬");

        assertThat(response.source()).isEqualTo(Source.LLM);
        assertThat(response.conditions().region()).isEqualTo("서울특별시");
        assertThat(FAKE.lastAuthorization()).isEqualTo("Bearer test-key");
        assertThat(FAKE.lastBody()).contains("openai/gpt-oss-120b").contains("\"type\":\"json_schema\"");
        assertThat(calls("success")).isEqualTo(before + 1);
    }

    @Test
    @DisplayName("범위 밖 값은 FALLBACK이고 invalid로 센다")
    void invalidValue() {
        FAKE.replyContent("{\"region\":\"화성\",\"dateFrom\":null,\"dateTo\":null,\"genre\":null,"
                + "\"maxPrice\":null,\"keyword\":null}");
        double before = calls("invalid");

        assertThat(assist("화성 공연").source()).isEqualTo(Source.FALLBACK);
        assertThat(calls("invalid")).isEqualTo(before + 1);
    }

    @Test
    @DisplayName("429는 FALLBACK이고 rate_limited로 따로 세며 재시도하지 않는다")
    void rateLimited() {
        FAKE.reply(429, "{\"error\":{\"message\":\"quota\"}}", 0);
        double before = calls("rate_limited");

        AssistResponse response = assist("강릉 연극");

        assertThat(response.source()).isEqualTo(Source.FALLBACK);
        assertThat(response.conditions().keyword()).isEqualTo("강릉 연극");
        assertThat(calls("rate_limited")).isEqualTo(before + 1);
        assertThat(calls("error")).isZero();
        assertThat(FAKE.requests()).isEqualTo(1);
    }

    @Test
    @DisplayName("타임아웃이면 FALLBACK이고 재시도하지 않는다")
    void timeout() {
        FAKE.reply(200, FakeChatCompletionsServer.completion("{}"), 3_000);
        double before = calls("timeout");

        long start = System.nanoTime();
        AssistResponse response = assist("강릉 연극");
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        assertThat(response.source()).isEqualTo(Source.FALLBACK);
        assertThat(elapsedMillis).isLessThan(2_500);
        assertThat(calls("timeout")).isEqualTo(before + 1);
        assertThat(FAKE.requests()).isEqualTo(1);
    }
}
