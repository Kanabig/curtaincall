package io.github.kanabig.curtaincall.queue.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kanabig.curtaincall.queue.repository.SessionConf;
import io.github.kanabig.curtaincall.queue.repository.SessionConf.Type;
import io.github.kanabig.curtaincall.queue.service.SessionConfService;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * [QUE-1] event.session의 회차 이벤트로 Redis conf를 갱신한다. Redis에는 processed_event 테이블이 없으므로 이벤트에 실린 전체 값을 덮어써 멱등을 얻고,
 * 순서가 바뀌어 늦게 온 이벤트는 봉투의 occurredAt(ms)을 버전으로 비교해 Lua 안에서 버린다. 오프셋은 레코드 단위로 처리 뒤에 커밋한다.
 */
@Component
public class SessionEventListener {

    private final ObjectMapper objectMapper;
    private final SessionConfService service;

    public SessionEventListener(ObjectMapper objectMapper, SessionConfService service) {
        this.objectMapper = objectMapper;
        this.service = service;
    }

    @KafkaListener(topics = "${curtaincall.topics.session:event.session}", groupId = "queue")
    void onSessionEvent(String message) {
        SessionConf conf = parse(message);
        if (conf != null) {
            service.apply(conf);
        }
    }

    /** 대기열이 쓰지 않는 이벤트 타입이면 null. */
    SessionConf parse(String message) {
        try {
            JsonNode root = objectMapper.readTree(message);
            if (root == null || !root.isObject()) {
                throw new InvalidSessionEventException("봉투가 JSON 객체가 아니다");
            }
            String type = text(root, "eventType");
            long version =
                    OffsetDateTime.parse(text(root, "occurredAt")).toInstant().toEpochMilli();
            JsonNode payload = root.get("payload");
            long sessionId = sessionId(root, payload);
            return switch (type) {
                case "SessionCreated", "SessionUpdated" -> upsert(sessionId, version, payload);
                case "SessionSuspended" -> new SessionConf(sessionId, Type.SUSPEND, version, 0, 0, 0);
                case "SessionDeleted" -> new SessionConf(sessionId, Type.DELETE, version, 0, 0, 0);
                default -> null;
            };
        } catch (JsonProcessingException | DateTimeParseException e) {
            throw new InvalidSessionEventException("회차 이벤트를 읽을 수 없다", e);
        }
    }

    private static SessionConf upsert(long sessionId, long version, JsonNode payload) {
        if (payload == null || !payload.isObject()) {
            throw new InvalidSessionEventException("payload가 없다");
        }
        long saleOpenAt =
                OffsetDateTime.parse(text(payload, "saleOpenAt")).toInstant().toEpochMilli();
        int admission = positiveInt(payload, "admissionPerSecond");
        int maxActive = positiveInt(payload, "maxActiveUsers");
        return new SessionConf(sessionId, Type.UPSERT, version, saleOpenAt, admission, maxActive);
    }

    private static long sessionId(JsonNode root, JsonNode payload) {
        if (payload != null && payload.hasNonNull("sessionId")) {
            return payload.get("sessionId").asLong();
        }
        try {
            return Long.parseLong(text(root, "aggregateId"));
        } catch (NumberFormatException e) {
            throw new InvalidSessionEventException("sessionId를 알 수 없다", e);
        }
    }

    private static int positiveInt(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isInt() || value.asInt() <= 0) {
            throw new InvalidSessionEventException(field + "가 양의 정수가 아니다");
        }
        return value.asInt();
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new InvalidSessionEventException(field + " 필드가 없다");
        }
        return value.asText();
    }
}
