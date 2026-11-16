package io.github.kanabig.curtaincall.searchassist.eval;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 평가 세트(JSON Lines). 첫 줄은 기준일을 담은 meta, 나머지는 문항. */
public record EvalSet(LocalDate today, List<EvalCase> cases) {

    public static final String RESOURCE = "/eval/search-assist-eval.jsonl";

    public record Expected(
            String region, String dateFrom, String dateTo, String genre, Integer maxPrice, String keyword) {}

    public record EvalCase(String query, Expected expected, String category) {}

    public static EvalSet load() {
        JsonMapper mapper = JsonMapper.builder().build();
        try (InputStream in = EvalSet.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("평가 세트를 찾을 수 없다: " + RESOURCE);
            }
            List<String> lines = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
                    .lines()
                    .filter(l -> !l.isBlank())
                    .toList();
            JsonNode meta = mapper.readTree(lines.get(0)).get("meta");
            LocalDate today = LocalDate.parse(meta.get("today").asString());
            List<EvalCase> cases = new ArrayList<>();
            for (String line : lines.subList(1, lines.size())) {
                cases.add(mapper.readValue(line, EvalCase.class));
            }
            return new EvalSet(today, List.copyOf(cases));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
