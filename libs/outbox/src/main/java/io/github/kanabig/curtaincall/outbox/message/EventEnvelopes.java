package io.github.kanabig.curtaincall.outbox.message;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/** 봉투 직렬화와 역직렬화. */
public class EventEnvelopes {

    // 밀리초 자릿수를 고정한다(ISO_OFFSET_DATE_TIME은 끝자리 0을 생략한다)
    private static final DateTimeFormatter OCCURRED_AT_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");

    private final ObjectMapper objectMapper;
    private final Clock clock;

    public EventEnvelopes(ObjectMapper objectMapper, Clock clock) {
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public String write(String eventId, String eventType, String aggregateId, Object payload) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", eventId);
        envelope.put("eventType", eventType);
        envelope.put("occurredAt", OCCURRED_AT_FORMAT.format(OffsetDateTime.now(clock)));
        envelope.put("aggregateId", aggregateId);
        envelope.put("payload", payload);
        try {
            return objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("이벤트 payload를 JSON으로 만들 수 없다: " + eventType, e);
        }
    }

    public EventEnvelope read(String json) {
        if (json == null) {
            throw new EventDeserializationException("메시지 값이 비어 있다");
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new EventDeserializationException("JSON이 아니다", e);
        }
        if (root == null || !root.isObject()) {
            throw new EventDeserializationException("봉투가 JSON 객체가 아니다");
        }
        return new EventEnvelope(
                required(root, "eventId"),
                required(root, "eventType"),
                required(root, "occurredAt"),
                required(root, "aggregateId"),
                root.get("payload"));
    }

    private static String required(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || !node.isTextual() || node.asText().isBlank()) {
            throw new EventDeserializationException("봉투에 " + field + " 필드가 없다");
        }
        return node.asText();
    }
}
