package io.github.kanabig.curtaincall.queue.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kanabig.curtaincall.queue.service.SessionConfService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * [QUE-8] reservation.session의 SessionAvailabilityChanged로 회차의 매진 여부를 반영한다. 이벤트에 실린 전체 상태와 회차별 version으로 덮어쓰고 더 큰
 * version만 적용하므로 중복·역순 전달에 안전하다. 다른 이벤트 타입은 무시한다.
 */
@Component
public class AvailabilityEventListener {

    static final String CHANGED = "SessionAvailabilityChanged";

    private final ObjectMapper objectMapper;
    private final SessionConfService service;

    public AvailabilityEventListener(ObjectMapper objectMapper, SessionConfService service) {
        this.objectMapper = objectMapper;
        this.service = service;
    }

    @KafkaListener(topics = "${curtaincall.topics.reservation-session:reservation.session}", groupId = "queue")
    void onAvailabilityEvent(String message) {
        try {
            JsonNode root = objectMapper.readTree(message);
            if (root == null || !root.isObject()) {
                throw new InvalidSessionEventException("봉투가 JSON 객체가 아니다");
            }
            if (CHANGED.equals(text(root, "eventType"))) {
                JsonNode payload = root.get("payload");
                if (payload == null || !payload.isObject()) {
                    throw new InvalidSessionEventException("payload가 없다");
                }
                JsonNode soldOut = payload.get("soldOut");
                if (soldOut == null || !soldOut.isBoolean()) {
                    throw new InvalidSessionEventException("soldOut이 boolean이 아니다");
                }
                service.applyAvailability(
                        longField(payload, "sessionId"), soldOut.asBoolean(), longField(payload, "version"));
            }
            ConsumedEventLog.processed(root);
        } catch (JsonProcessingException e) {
            throw new InvalidSessionEventException("매진 이벤트를 읽을 수 없다", e);
        }
    }

    private static long longField(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
            throw new InvalidSessionEventException(field + "가 정수가 아니다");
        }
        return value.asLong();
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new InvalidSessionEventException(field + " 필드가 없다");
        }
        return value.asText();
    }
}
