package io.github.kanabig.curtaincall.searchassist.client;

import io.github.kanabig.curtaincall.searchassist.config.SearchAssistProperties;
import io.github.kanabig.curtaincall.searchassist.config.SearchAssistProperties.Compatible;
import io.github.kanabig.curtaincall.searchassist.config.SearchAssistProperties.ProviderType;
import java.io.InterruptedIOException;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * OpenAI 호환 {@code /chat/completions}로 조건을 뽑는다(Gemini, Groq, K-EXAONE). 제공자 차이는 설정(base URL, 모델, 응답 형식, 추론 강도)뿐이다. 응답 형식이
 * json_schema가 아니면 스키마를 강제하지 못하므로 텍스트에서 첫 JSON 객체를 꺼내 읽고, 어느 쪽이든 결과는 서버가 다시 검증한다. 추론 출력(reasoning_content)은 읽지 않는다.
 */
public class OpenAiCompatibleConditionExtractor implements LlmConditionExtractor {

    private final RestClient client;
    private final ObjectMapper objectMapper;
    private final Compatible provider;
    private final String systemPrompt = ConditionSchema.systemPromptWithFormat();
    private final Map<String, Object> schema = ConditionSchema.schema();
    private final LongAdder inputTokens = new LongAdder();
    private final LongAdder outputTokens = new LongAdder();

    /** 키가 없으면 클라이언트를 만들지 않고 {@link #enabled()}가 false다. mode=llm인데 키가 없으면 기동 때 실패한다. */
    public OpenAiCompatibleConditionExtractor(
            SearchAssistProperties properties, ProviderType type, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.provider = properties.provider(type);
        if (properties.mode() == SearchAssistProperties.Mode.MOCK) {
            this.client = null;
        } else if (provider.hasKey()) {
            // HTTP/1.1로 고정해 h2c 업그레이드 시도를 막는다. 재시도 없음, 타임아웃은 요청 하나에 건다
            HttpClient httpClient = HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .connectTimeout(properties.llm().timeout())
                    .build();
            JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
            factory.setReadTimeout(properties.llm().timeout());
            this.client = RestClient.builder()
                    .baseUrl(provider.baseUrl())
                    .requestFactory(factory)
                    .build();
        } else if (properties.mode() == SearchAssistProperties.Mode.LLM) {
            throw new IllegalStateException("search-assist.mode=llm 이지만 " + type + " API 키가 비어 있다");
        } else {
            this.client = null;
        }
    }

    @Override
    public boolean enabled() {
        return client != null;
    }

    @Override
    public Usage usage() {
        return new Usage(inputTokens.sum(), outputTokens.sum());
    }

    @Override
    public RawConditions extract(String query, LocalDate today) {
        String response;
        try {
            response = client.post()
                    .uri(provider.path())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + provider.apiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(objectMapper.writeValueAsString(request(query, today)))
                    .retrieve()
                    .body(String.class);
        } catch (HttpClientErrorException.TooManyRequests e) {
            throw new ExtractionException("llm rate limited", e, false, true);
        } catch (RuntimeException e) {
            throw new ExtractionException("llm call failed", e, isTimeout(e));
        }
        return parse(response);
    }

    private Map<String, Object> request(String query, LocalDate today) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", provider.model());
        body.put(
                "messages",
                List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", ConditionSchema.userMessage(query, today))));
        body.put("max_tokens", provider.maxTokens());
        body.put("temperature", 0);
        if (!provider.reasoningEffort().isBlank()) {
            body.put("reasoning_effort", provider.reasoningEffort());
        }
        switch (provider.responseFormat()) {
            case JSON_SCHEMA ->
                body.put(
                        "response_format",
                        Map.of(
                                "type",
                                "json_schema",
                                "json_schema",
                                Map.of("name", "search_conditions", "strict", true, "schema", schema)));
            case JSON_OBJECT -> body.put("response_format", Map.of("type", "json_object"));
            case NONE -> {}
        }
        return body;
    }

    private RawConditions parse(String response) {
        String content;
        try {
            JsonNode root = objectMapper.readTree(response == null ? "" : response);
            JsonNode usage = root.path("usage");
            JsonNode message = root.path("choices").path(0).path("message");
            JsonNode text = message.path("content");
            if (!text.isString()) {
                throw new ExtractionException("llm returned no text", null, false);
            }
            content = text.asString();
            inputTokens.add(usage.path("prompt_tokens").asLong(0));
            outputTokens.add(usage.path("completion_tokens").asLong(0));
        } catch (JacksonException e) {
            throw new ExtractionException("llm returned malformed response", e, false);
        }
        return ConditionJson.parse(objectMapper, ConditionJson.firstObject(content));
    }

    private static boolean isTimeout(Throwable e) {
        List<Throwable> seen = new ArrayList<>();
        for (Throwable t = e; t != null && !seen.contains(t); t = t.getCause()) {
            seen.add(t);
            if (t instanceof HttpTimeoutException || t instanceof InterruptedIOException) {
                return true;
            }
        }
        return false;
    }
}
