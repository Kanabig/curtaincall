package io.github.kanabig.curtaincall.searchassist.eval;

import io.github.kanabig.curtaincall.searchassist.client.ClaudeConditionExtractor;
import io.github.kanabig.curtaincall.searchassist.client.MockConditionExtractor;
import io.github.kanabig.curtaincall.searchassist.config.SearchAssistProperties;
import io.github.kanabig.curtaincall.searchassist.config.SearchAssistProperties.Breaker;
import io.github.kanabig.curtaincall.searchassist.config.SearchAssistProperties.Llm;
import io.github.kanabig.curtaincall.searchassist.config.SearchAssistProperties.Mode;
import io.github.kanabig.curtaincall.searchassist.dto.AssistResponse;
import io.github.kanabig.curtaincall.searchassist.dto.SearchConditions;
import io.github.kanabig.curtaincall.searchassist.eval.EvalSet.EvalCase;
import io.github.kanabig.curtaincall.searchassist.eval.EvalSet.Expected;
import io.github.kanabig.curtaincall.searchassist.service.ConditionValidator;
import io.github.kanabig.curtaincall.searchassist.service.SearchAssistService;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import tools.jackson.databind.json.JsonMapper;

/** 평가 세트를 실제 서비스 경로(추출 → 검증 → fallback)로 돌리고 지표를 모은다. */
public final class EvalRunner {

    public static final List<String> FIELDS = List.of("region", "dateFrom", "dateTo", "genre", "maxPrice", "keyword");
    public static final List<String> CATEGORIES = List.of("normal", "ambiguous", "adversarial");
    /** 악의 문항에서 열거형·범위로 검사하는 필드. keyword는 길이 제한만 있어 제외한다. */
    private static final List<String> GUARDED = List.of("region", "dateFrom", "dateTo", "genre", "maxPrice");

    public record Outcome(EvalCase evalCase, AssistResponse response, long nanos) {}

    /** usage가 null이면 토큰을 쓰지 않는 경로(Mock). 단가는 100만 토큰당 달러이고 모르면 null. */
    public record Run(
            String label,
            EvalSet set,
            List<Outcome> outcomes,
            ClaudeConditionExtractor.Usage usage,
            Double inputPrice,
            Double outputPrice) {}

    private EvalRunner() {}

    public static Run runMock(EvalSet set) {
        SearchAssistProperties props = new SearchAssistProperties(Mode.MOCK, llm("", ""), breaker());
        return run("Mock", set, service(set, props, claude(props)), null, null, null);
    }

    /** 키는 호출하는 쪽이 환경변수에서 읽어 넘긴다. 모델은 빈 값이면 설정 기본값. */
    public static Run runLlm(EvalSet set, String apiKey, String model) {
        SearchAssistProperties props = new SearchAssistProperties(Mode.LLM, llm(apiKey, model), breaker());
        ClaudeConditionExtractor claude = claude(props);
        // 단가는 모델마다 달라 기본값(haiku-5-5)이 아니면 환경변수로 받는다
        boolean haiku = props.llm().model().equals("claude-haiku-5-5");
        Double in = price("SEARCH_ASSIST_EVAL_INPUT_PRICE", haiku ? 0.10 : null);
        Double out = price("SEARCH_ASSIST_EVAL_OUTPUT_PRICE", haiku ? 0.50 : null);
        return run("LLM " + props.llm().model(), set, service(set, props, claude), claude, in, out);
    }

    private static Double price(String env, Double fallback) {
        String v = System.getenv(env);
        return v == null || v.isBlank() ? fallback : Double.valueOf(v);
    }

    private static ClaudeConditionExtractor claude(SearchAssistProperties props) {
        return new ClaudeConditionExtractor(props, JsonMapper.builder().build());
    }

    private static Run run(
            String label,
            EvalSet set,
            SearchAssistService service,
            ClaudeConditionExtractor claude,
            Double inputPrice,
            Double outputPrice) {
        List<Outcome> outcomes = new ArrayList<>();
        for (EvalCase c : set.cases()) {
            long start = System.nanoTime();
            AssistResponse response = service.assist(c.query());
            outcomes.add(new Outcome(c, response, System.nanoTime() - start));
        }
        return new Run(label, set, outcomes, claude == null ? null : claude.usage(), inputPrice, outputPrice);
    }

    private static Llm llm(String apiKey, String model) {
        String m = model == null || model.isBlank() ? "claude-haiku-5-5" : model;
        return new Llm(apiKey, "", m, "low", Duration.ofSeconds(2), 1000);
    }

    private static Breaker breaker() {
        return new Breaker(20, 10, 50f, Duration.ofSeconds(30));
    }

    private static SearchAssistService service(
            EvalSet set, SearchAssistProperties props, ClaudeConditionExtractor claude) {
        Clock clock = Clock.fixed(
                ZonedDateTime.of(set.today().atTime(12, 0), ZoneId.of("Asia/Seoul"))
                        .toInstant(),
                ZoneId.of("Asia/Seoul"));
        // 평가가 끝나기 전에 열리지 않도록 임계치를 사실상 끈다
        CircuitBreaker breaker = CircuitBreaker.of(
                "eval",
                CircuitBreakerConfig.custom()
                        .slidingWindowSize(1000)
                        .minimumNumberOfCalls(1000)
                        .build());
        return new SearchAssistService(
                claude,
                new MockConditionExtractor(),
                new ConditionValidator(),
                breaker,
                props,
                clock,
                new SimpleMeterRegistry());
    }

    // 지표

    public static boolean fieldMatches(String field, Expected e, SearchConditions a) {
        return switch (field) {
            case "region" -> Objects.equals(e.region(), a.region());
            case "dateFrom" -> Objects.equals(e.dateFrom(), str(a.dateFrom()));
            case "dateTo" -> Objects.equals(e.dateTo(), str(a.dateTo()));
            case "genre" -> Objects.equals(e.genre(), a.genre());
            case "maxPrice" -> Objects.equals(e.maxPrice(), a.maxPrice());
            case "keyword" -> Objects.equals(norm(e.keyword()), norm(a.keyword()));
            default -> throw new IllegalArgumentException(field);
        };
    }

    public static boolean allMatch(Outcome o) {
        return FIELDS.stream()
                .allMatch(f ->
                        fieldMatches(f, o.evalCase().expected(), o.response().conditions()));
    }

    /** 열거형·범위 필드가 모두 null이거나 기대값과 같으면 true. 기대 밖 값이 하나라도 있으면 false. */
    public static boolean noUnexpectedValue(Outcome o) {
        Expected e = o.evalCase().expected();
        SearchConditions a = o.response().conditions();
        return GUARDED.stream().allMatch(f -> isNull(f, a) || fieldMatches(f, e, a));
    }

    private static boolean isNull(String field, SearchConditions a) {
        return switch (field) {
            case "region" -> a.region() == null;
            case "dateFrom" -> a.dateFrom() == null;
            case "dateTo" -> a.dateTo() == null;
            case "genre" -> a.genre() == null;
            case "maxPrice" -> a.maxPrice() == null;
            default -> throw new IllegalArgumentException(field);
        };
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static String norm(String s) {
        if (s == null) {
            return null;
        }
        String n = s.strip().replaceAll("\\s+", " ");
        return n.isEmpty() ? null : n;
    }

    public static String render(Run run) {
        List<Outcome> all = run.outcomes();
        StringBuilder sb = new StringBuilder();
        sb.append("## ")
                .append(run.label())
                .append(" (기준일 ")
                .append(run.set().today())
                .append(", ")
                .append(all.size())
                .append("문항)\n\n");

        sb.append("### 필드별 정확도\n\n| 구분 | 문항 |");
        FIELDS.forEach(f -> sb.append(' ').append(f).append(" |"));
        sb.append(" 전체 일치 |\n|---|---|")
                .append("---|".repeat(FIELDS.size() + 1))
                .append('\n');
        row(sb, "전체", all);
        for (String category : CATEGORIES) {
            row(
                    sb,
                    category,
                    all.stream()
                            .filter(o -> o.evalCase().category().equals(category))
                            .toList());
        }

        Map<String, Integer> sources = new LinkedHashMap<>();
        for (var s : new String[] {"LLM", "MOCK", "FALLBACK"}) {
            sources.put(s, 0);
        }
        all.forEach(o -> sources.merge(o.response().source().name(), 1, Integer::sum));
        sb.append("\n### source 분포\n\n| source | 건수 | 비율 |\n|---|---|---|\n");
        sources.forEach((k, v) -> sb.append(String.format("| %s | %d | %.1f%% |%n", k, v, 100.0 * v / all.size())));

        long[] sorted = all.stream().mapToLong(Outcome::nanos).sorted().toArray();
        sb.append(String.format(
                "%n### 지연%n%n| p50 | p95 | max |%n|---|---|---|%n| %s | %s | %s |%n",
                ms(percentile(sorted, 50)), ms(percentile(sorted, 95)), ms(sorted[sorted.length - 1])));

        sb.append(tokens(run, all.size()));

        List<Outcome> adversarial = all.stream()
                .filter(o -> o.evalCase().category().equals("adversarial"))
                .toList();
        long safe = adversarial.stream().filter(EvalRunner::noUnexpectedValue).count();
        sb.append(String.format(
                "%n### 악의·범위 밖 문항%n%n열거형·범위 필드(region, genre, dateFrom, dateTo, maxPrice)에 기대 밖 값이 없는 문항: %d / %d%n",
                safe, adversarial.size()));

        sb.append("\n### 틀린 문항\n\n| 구분 | 검색어 | 틀린 필드 (기대 → 실제) | source |\n|---|---|---|---|\n");
        for (Outcome o : all) {
            List<String> diffs = new ArrayList<>();
            for (String f : FIELDS) {
                if (!fieldMatches(f, o.evalCase().expected(), o.response().conditions())) {
                    diffs.add(f + " " + value(f, o.evalCase().expected()) + " → "
                            + value(f, o.response().conditions()));
                }
            }
            if (!diffs.isEmpty()) {
                sb.append(String.format(
                        "| %s | %s | %s | %s |%n",
                        o.evalCase().category(),
                        o.evalCase().query().replace("|", "\\|"),
                        String.join("; ", diffs).replace("|", "\\|"),
                        o.response().source()));
            }
        }
        return sb.toString();
    }

    static String tokens(Run run, int cases) {
        var u = run.usage();
        String header = "%n### 토큰·비용%n%n| 입력 토큰 | 출력 토큰 | 문항당 입력 | 문항당 출력 | 추정 비용 |%n|---|---|---|---|---|%n";
        if (u == null) {
            return String.format(header + "| 0 | 0 | 0 | 0 | - |%n");
        }
        String cost = run.inputPrice() == null || run.outputPrice() == null
                ? "-"
                : String.format(
                        "$%.5f",
                        (u.inputTokens() * run.inputPrice() + u.outputTokens() * run.outputPrice()) / 1_000_000.0);
        return String.format(
                header + "| %d | %d | %.1f | %.1f | %s |%n",
                u.inputTokens(),
                u.outputTokens(),
                (double) u.inputTokens() / cases,
                (double) u.outputTokens() / cases,
                cost);
    }

    private static void row(StringBuilder sb, String name, List<Outcome> rows) {
        sb.append("| ").append(name).append(" | ").append(rows.size()).append(" |");
        for (String f : FIELDS) {
            long ok = rows.stream()
                    .filter(o -> fieldMatches(
                            f, o.evalCase().expected(), o.response().conditions()))
                    .count();
            sb.append(String.format(" %.1f%% |", 100.0 * ok / rows.size()));
        }
        long full = rows.stream().filter(EvalRunner::allMatch).count();
        sb.append(String.format(" %.1f%% (%d) |%n", 100.0 * full / rows.size(), full));
    }

    private static String value(String field, Object holder) {
        if (holder instanceof Expected e) {
            return String.valueOf(
                    switch (field) {
                        case "region" -> e.region();
                        case "dateFrom" -> e.dateFrom();
                        case "dateTo" -> e.dateTo();
                        case "genre" -> e.genre();
                        case "maxPrice" -> e.maxPrice();
                        default -> e.keyword();
                    });
        }
        SearchConditions a = (SearchConditions) holder;
        return String.valueOf(
                switch (field) {
                    case "region" -> a.region();
                    case "dateFrom" -> a.dateFrom();
                    case "dateTo" -> a.dateTo();
                    case "genre" -> a.genre();
                    case "maxPrice" -> a.maxPrice();
                    default -> a.keyword();
                });
    }

    static long percentile(long[] sorted, int p) {
        int index = (int) Math.ceil(p / 100.0 * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(index, sorted.length - 1))];
    }

    private static String ms(long nanos) {
        return String.format("%.2f ms", nanos / 1_000_000.0);
    }
}
