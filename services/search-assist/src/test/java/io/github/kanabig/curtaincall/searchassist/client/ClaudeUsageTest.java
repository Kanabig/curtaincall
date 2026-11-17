package io.github.kanabig.curtaincall.searchassist.client;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.kanabig.curtaincall.searchassist.config.SearchAssistProperties;
import io.github.kanabig.curtaincall.searchassist.config.SearchAssistProperties.Breaker;
import io.github.kanabig.curtaincall.searchassist.config.SearchAssistProperties.Llm;
import io.github.kanabig.curtaincall.searchassist.config.SearchAssistProperties.Mode;
import io.github.kanabig.curtaincall.searchassist.config.SearchAssistProperties.ProviderType;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** SRC-4. 평가 실행기가 비용을 계산할 수 있게 호출마다 토큰 사용량이 쌓인다. */
class ClaudeUsageTest {

    @Test
    @DisplayName("SRC-4: 성공한 호출의 입력·출력 토큰이 합산된다")
    void accumulatesUsage() {
        try (FakeAnthropicServer fake = new FakeAnthropicServer()) {
            fake.replyText("{\"region\":null,\"dateFrom\":null,\"dateTo\":null,\"genre\":null,\"maxPrice\":null,"
                    + "\"keyword\":\"위키드\"}");
            SearchAssistProperties props = new SearchAssistProperties(
                    Mode.LLM,
                    new Llm(
                            "test-key",
                            fake.baseUrl(),
                            "claude-haiku-5-5",
                            "low",
                            Duration.ofSeconds(2),
                            1000,
                            ProviderType.CLAUDE),
                    new Breaker(20, 10, 50f, Duration.ofSeconds(30)),
                    Map.of());
            ClaudeConditionExtractor extractor =
                    new ClaudeConditionExtractor(props, JsonMapper.builder().build());

            extractor.extract("위키드", LocalDate.of(2026, 11, 16));
            extractor.extract("위키드", LocalDate.of(2026, 11, 16));

            assertThat(extractor.usage()).isEqualTo(new Usage(20, 20));
        }
    }
}
