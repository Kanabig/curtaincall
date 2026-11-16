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

/** SRC-3. 하루 호출 한도. */
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SearchAssistQuotaTests {

    static final FakeAnthropicServer FAKE = new FakeAnthropicServer();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("search-assist.llm.api-key", () -> "test-key");
        registry.add("search-assist.llm.base-url", FAKE::baseUrl);
        registry.add("search-assist.llm.timeout", () -> "500ms");
        registry.add("search-assist.llm.daily-limit", () -> "2");
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
    @DisplayName("하루 호출 한도를 넘으면 그날은 Mock으로 처리하고 LLM 서버는 더 호출되지 않는다")
    void quotaFallsBackToMock() {
        FAKE.reset();
        FAKE.replyText("{\"region\":\"서울특별시\",\"keyword\":null}");

        assertThat(assist("서울 공연").source()).isEqualTo(Source.LLM);
        assertThat(assist("서울 공연").source()).isEqualTo(Source.LLM);
        AssistResponse third = assist("부산 연극");

        assertThat(third.source()).isEqualTo(Source.MOCK);
        assertThat(third.conditions().region()).isEqualTo("부산광역시");
        assertThat(FAKE.requests()).isEqualTo(2);
    }
}
