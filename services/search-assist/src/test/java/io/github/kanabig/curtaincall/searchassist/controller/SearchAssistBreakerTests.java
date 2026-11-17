package io.github.kanabig.curtaincall.searchassist.controller;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.kanabig.curtaincall.searchassist.client.FakeAnthropicServer;
import io.github.kanabig.curtaincall.searchassist.dto.AssistResponse;
import io.github.kanabig.curtaincall.searchassist.dto.Source;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** SRC-2. CircuitBreaker. */
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SearchAssistBreakerTests {

    static final FakeAnthropicServer FAKE = new FakeAnthropicServer();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("search-assist.llm.provider", () -> "claude");
        registry.add("search-assist.llm.api-key", () -> "test-key");
        registry.add("search-assist.llm.base-url", FAKE::baseUrl);
        registry.add("search-assist.llm.timeout", () -> "500ms");
        registry.add("search-assist.breaker.window-size", () -> "4");
        registry.add("search-assist.breaker.minimum-calls", () -> "4");
        registry.add("search-assist.breaker.open-duration", () -> "1m");
    }

    @AfterAll
    static void stop() {
        FAKE.close();
    }

    @Autowired
    TestRestTemplate rest;

    private AssistResponse assist(String query) {
        return rest.postForEntity("/api/v1/search/assist", Map.of("query", query), AssistResponse.class)
                .getBody();
    }

    @Test
    @DisplayName("실패율이 임계치를 넘으면 CircuitBreaker가 열려 LLM 서버를 더 부르지 않고 바로 FALLBACK")
    void breakerOpens() {
        FAKE.reset();
        FAKE.reply(500, "{\"type\":\"error\",\"error\":{\"type\":\"api_error\",\"message\":\"boom\"}}", 0);

        for (int i = 0; i < 8; i++) {
            assertThat(assist("서울 공연 " + i).source()).isEqualTo(Source.FALLBACK);
        }

        // 창 크기 4: 4번 실패한 뒤 열리므로 서버에는 4번만 간다
        assertThat(FAKE.requests()).isEqualTo(4);
        // LLM이 복구돼도 열려 있는 동안은 부르지 않는다
        FAKE.replyText("{\"region\":\"서울특별시\",\"keyword\":null}");
        assertThat(assist("서울 공연").source()).isEqualTo(Source.FALLBACK);
        assertThat(FAKE.requests()).isEqualTo(4);
    }
}
