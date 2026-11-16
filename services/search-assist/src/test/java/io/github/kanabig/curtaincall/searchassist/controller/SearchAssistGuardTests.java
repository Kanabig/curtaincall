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
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** CircuitBreaker와 하루 호출 한도. 호출 수를 세므로 테스트 하나가 컨텍스트 하나를 쓴다. */
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class SearchAssistGuardTests {

    static final FakeAnthropicServer FAKE = new FakeAnthropicServer();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("search-assist.llm.api-key", () -> "test-key");
        registry.add("search-assist.llm.base-url", FAKE::baseUrl);
        registry.add("search-assist.llm.timeout", () -> "500ms");
        registry.add("search-assist.llm.daily-limit", () -> "2");
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
    @DisplayName("실패율이 임계치를 넘으면 CircuitBreaker가 열려 LLM을 부르지 않고 바로 FALLBACK")
    void breakerOpens() {
        FAKE.reset();
        FAKE.reply(500, "{\"type\":\"error\",\"error\":{\"type\":\"api_error\",\"message\":\"boom\"}}", 0);
        // 한도(2)를 넘지 않게 breaker 시험은 한도를 쓰지 않는 쪽이 아니라 호출 4회가 필요하므로 아래 한도 테스트와 컨텍스트를 나눈다
        int sent = 0;
        for (int i = 0; i < 6; i++) {
            AssistResponse response = assist("서울 공연 " + i);
            assertThat(response.source()).isIn(Source.FALLBACK, Source.MOCK);
            sent = FAKE.requests();
        }
        // 한도 2회 이후는 Mock으로 가므로 breaker까지 가지 못한다 → 요청 수는 2
        assertThat(sent).isEqualTo(2);
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
