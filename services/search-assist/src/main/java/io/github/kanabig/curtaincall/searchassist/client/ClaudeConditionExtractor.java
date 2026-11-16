package io.github.kanabig.curtaincall.searchassist.client;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.JsonOutputFormat;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import io.github.kanabig.curtaincall.searchassist.config.SearchAssistProperties;
import java.io.InterruptedIOException;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;
import tools.jackson.databind.ObjectMapper;

/**
 * Claude API(structured outputs)로 조건을 뽑는다. 스키마 강제는 파싱 실패를 줄이는 장치일 뿐이고, 응답은 신뢰하지 않는다. 사용자 입력은 사용자 메시지로만 넣는다.
 */
public class ClaudeConditionExtractor implements LlmConditionExtractor {

    private final AnthropicClient client;
    private final ObjectMapper objectMapper;
    private final String model;
    private final OutputConfig.Effort effort;
    private final Map<String, JsonValue> schema;
    private final LongAdder inputTokens = new LongAdder();
    private final LongAdder outputTokens = new LongAdder();

    /**
     * 키가 있고 mode가 mock이 아닐 때만 클라이언트를 만든다. 재시도는 지연만 늘리고 실패해도 키워드 검색이라는 대안이 있으므로 0회, 타임아웃은 요청 전체에 건다. 키는 설정에서만
     * 읽는다.
     */
    public ClaudeConditionExtractor(SearchAssistProperties properties, ObjectMapper objectMapper) {
        SearchAssistProperties.Llm llm = properties.llm();
        this.objectMapper = objectMapper;
        this.model = llm.model();
        this.effort = OutputConfig.Effort.of(llm.effort());
        this.schema = schema();
        if (properties.mode() == SearchAssistProperties.Mode.MOCK) {
            this.client = null;
        } else if (llm.hasKey()) {
            AnthropicOkHttpClient.Builder builder = AnthropicOkHttpClient.builder()
                    .apiKey(llm.apiKey())
                    .timeout(llm.timeout())
                    .maxRetries(0);
            if (!llm.baseUrl().isBlank()) {
                builder.baseUrl(llm.baseUrl());
            }
            this.client = builder.build();
        } else if (properties.mode() == SearchAssistProperties.Mode.LLM) {
            throw new IllegalStateException("search-assist.mode=llm 이지만 API 키가 비어 있다");
        } else {
            this.client = null;
        }
    }

    @Override
    public Usage usage() {
        return new Usage(inputTokens.sum(), outputTokens.sum());
    }

    @Override
    public boolean enabled() {
        return client != null;
    }

    @Override
    public RawConditions extract(String query, LocalDate today) {
        Message message;
        try {
            message = client.messages().create(request(query, today));
        } catch (RuntimeException e) {
            throw new ExtractionException("llm call failed", e, isTimeout(e));
        }
        inputTokens.add(message.usage().inputTokens());
        outputTokens.add(message.usage().outputTokens());
        String json = message.content().stream()
                .flatMap(block -> block.text().stream())
                .map(text -> text.text())
                .findFirst()
                .orElseThrow(() -> ExtractionException.badResponse("llm returned no text", null));
        return ConditionJson.parse(objectMapper, json);
    }

    private MessageCreateParams request(String query, LocalDate today) {
        JsonOutputFormat format = JsonOutputFormat.builder()
                .schema(JsonOutputFormat.Schema.builder()
                        .additionalProperties(schema)
                        .build())
                .build();
        return MessageCreateParams.builder()
                .model(model)
                .maxTokens(1024L)
                .system(ConditionSchema.SYSTEM_PROMPT)
                .outputConfig(
                        OutputConfig.builder().effort(effort).format(format).build())
                .addUserMessage(ConditionSchema.userMessage(query, today))
                .build();
    }

    private static boolean isTimeout(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof InterruptedIOException) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, JsonValue> schema() {
        Map<String, JsonValue> root = new LinkedHashMap<>();
        ConditionSchema.schema().forEach((key, value) -> root.put(key, JsonValue.from(value)));
        return root;
    }
}
