package io.github.kanabig.curtaincall.searchassist.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.kanabig.curtaincall.searchassist.config.SearchAssistProperties.ProviderType;
import io.github.kanabig.curtaincall.searchassist.domain.KopisCodes;
import io.github.kanabig.curtaincall.searchassist.eval.EvalSet.EvalCase;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.convert.DurationStyle;

/**
 * SRC-4. Mock은 항상 돌고 결과 표를 build/reports/search-assist-eval에 쓴다. 정확도 기준치는 두지 않는다. LLM은 {@code searchAssistEval} 태스크로, API 키가 있을
 * 때만 돈다.
 */
class SearchAssistEvaluationTest {

    private static final Logger log = LoggerFactory.getLogger(SearchAssistEvaluationTest.class);

    private static final Path REPORT_DIR =
            Path.of(System.getProperty("searchAssist.evalReportDir", "build/reports/search-assist-eval"));

    private final EvalSet set = EvalSet.load();

    @Test
    @DisplayName("SRC-4: 평가 세트는 100문항(정상 70, 모호 20, 악의 10)이고 기대값이 규칙 안에 있다")
    void setIsWellFormed() {
        assertThat(set.cases()).hasSize(100);
        assertThat(set.cases().stream().collect(Collectors.groupingBy(EvalCase::category, Collectors.counting())))
                .containsEntry("normal", 70L)
                .containsEntry("ambiguous", 20L)
                .containsEntry("adversarial", 10L);
        assertThat(set.cases().stream().map(EvalCase::query).distinct()).hasSize(100);
        for (EvalCase c : set.cases()) {
            var e = c.expected();
            if (e.region() != null) {
                assertThat(KopisCodes.REGIONS).as(c.query()).contains(e.region());
            }
            if (e.genre() != null) {
                assertThat(KopisCodes.GENRES).as(c.query()).contains(e.genre());
            }
            LocalDate from = e.dateFrom() == null ? null : LocalDate.parse(e.dateFrom());
            LocalDate to = e.dateTo() == null ? null : LocalDate.parse(e.dateTo());
            if (from != null && to != null) {
                assertThat(from).as(c.query()).isBeforeOrEqualTo(to);
            }
            if (from != null) {
                assertThat(from)
                        .as(c.query())
                        .isBetween(set.today(), set.today().plusDays(365));
            }
        }
    }

    @Test
    @DisplayName("SRC-4: Mock 평가를 돌려 결과 표를 남긴다")
    void mockEvaluation() throws IOException {
        EvalRunner.Run run = EvalRunner.runMock(set);

        assertThat(run.outcomes()).hasSize(100);
        write("mock.md", EvalRunner.render(run));
    }

    @Test
    @EnabledIfSystemProperty(named = "searchAssist.evalLlm", matches = "true")
    @DisplayName("SRC-4: 선택한 제공자의 키가 있으면 같은 세트로 실제 모델을 평가한다")
    void llmEvaluation() throws IOException {
        // SEARCH_ASSIST_EVAL_PROVIDER: gemini(기본)|groq|exaone|claude, SEARCH_ASSIST_EVAL_TIMEOUT: 원래 지연을 보려면 30s처럼 늘린다
        ProviderType type = ProviderType.valueOf(
                env("SEARCH_ASSIST_EVAL_PROVIDER", "gemini").toUpperCase());
        Duration timeout = DurationStyle.detectAndParse(env("SEARCH_ASSIST_EVAL_TIMEOUT", "2s"));
        assumeTrue(EvalRunner.hasKey(EvalRunner.loadProperties(), type), type + " API 키가 없다");

        EvalRunner.Run run = EvalRunner.runLlm(set, type, System.getenv("SEARCH_ASSIST_EVAL_MODEL"), timeout);

        String suffix = timeout.equals(Duration.ofSeconds(2)) ? "" : "-timeout" + timeout.toSeconds() + "s";
        write("llm-" + type.name().toLowerCase() + suffix + ".md", EvalRunner.render(run));
    }

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? fallback : v;
    }

    private void write(String name, String markdown) throws IOException {
        Files.createDirectories(REPORT_DIR);
        Files.writeString(REPORT_DIR.resolve(name), markdown);
        log.info("\n{}", markdown);
    }
}
