package io.github.kanabig.curtaincall.searchassist.eval;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.kanabig.curtaincall.searchassist.client.FakeChatCompletionsServer;
import io.github.kanabig.curtaincall.searchassist.client.OpenAiCompatibleConditionExtractor;
import io.github.kanabig.curtaincall.searchassist.config.SearchAssistProperties;
import io.github.kanabig.curtaincall.searchassist.config.SearchAssistProperties.Breaker;
import io.github.kanabig.curtaincall.searchassist.config.SearchAssistProperties.Compatible;
import io.github.kanabig.curtaincall.searchassist.config.SearchAssistProperties.Llm;
import io.github.kanabig.curtaincall.searchassist.config.SearchAssistProperties.Mode;
import io.github.kanabig.curtaincall.searchassist.config.SearchAssistProperties.ProviderType;
import io.github.kanabig.curtaincall.searchassist.config.SearchAssistProperties.ResponseFormat;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** SRC-4. 평가 실행기는 처음부터 인증 오류만 나면 나머지 문항을 돌리지 않고 사유를 표에 남긴다. */
class EvalAuthAbortTest {

    private static final String SUCCESS = "{\"region\":null,\"dateFrom\":null,\"dateTo\":null,\"genre\":null,"
            + "\"maxPrice\":null,\"keyword\":\"위키드\"}";

    private static SearchAssistProperties props(FakeChatCompletionsServer fake) {
        Compatible provider = new Compatible(
                "test-key",
                fake.baseUrl(),
                "/chat/completions",
                "m",
                ResponseFormat.JSON_SCHEMA,
                "",
                1024,
                Duration.ZERO);
        return new SearchAssistProperties(
                Mode.LLM,
                new Llm("", "", "claude-haiku-5-5", "low", Duration.ofSeconds(2), 1000, ProviderType.GROQ),
                new Breaker(20, 10, 50f, Duration.ofSeconds(30)),
                Map.of("groq", provider));
    }

    @Test
    @DisplayName("앞 5건이 모두 401이면 5건에서 중단하고 보고서에 사유가 남는다")
    void abortsOnLeadingAuthFailures() {
        try (FakeChatCompletionsServer fake = new FakeChatCompletionsServer()) {
            fake.reply(401, "{\"error\":{\"message\":\"Invalid API Key\"}}", 0);
            SearchAssistProperties props = props(fake);
            var extractor = new OpenAiCompatibleConditionExtractor(
                    props, ProviderType.GROQ, JsonMapper.builder().build());

            EvalRunner.Run run = EvalRunner.runWith(
                    EvalSet.load(), props, extractor, "LLM groq", Duration.ZERO, EvalRunner.AUTH_ABORT_AFTER);

            assertThat(run.outcomes()).hasSize(5);
            assertThat(fake.requests()).isEqualTo(5);
            assertThat(run.abortReason()).contains("인증 오류").contains("5건");
            assertThat(EvalRunner.render(run)).contains("### 중단").contains("error");
        }
    }

    @Test
    @DisplayName("인증 오류 뒤에 성공이 한 번이라도 있으면 중단하지 않는다")
    void doesNotAbortWhenNotLeadingStreak() {
        try (FakeChatCompletionsServer fake = new FakeChatCompletionsServer()) {
            fake.replyContent(SUCCESS);
            SearchAssistProperties props = props(fake);
            var extractor = new OpenAiCompatibleConditionExtractor(
                    props, ProviderType.GROQ, JsonMapper.builder().build());

            EvalRunner.Run run = EvalRunner.runWith(
                    EvalSet.load(), props, extractor, "LLM groq", Duration.ZERO, EvalRunner.AUTH_ABORT_AFTER);

            assertThat(run.abortReason()).isNull();
            assertThat(run.outcomes()).hasSize(100);
        }
    }
}
