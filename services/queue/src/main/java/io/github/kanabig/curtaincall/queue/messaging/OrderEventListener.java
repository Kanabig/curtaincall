package io.github.kanabig.curtaincall.queue.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kanabig.curtaincall.queue.repository.QueueRepository;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * [QUE-6] reservation.order의 ReservationConfirmed를 받으면 그 사용자를 활성 목록에서 뺀다. 자리가 비어 다음 대기자가 들어온다. 이미 없는 사용자를 다시 빼도 결과가 같아 중복
 * 전달에 안전하다(멱등). 다른 이벤트 타입은 무시한다.
 */
@Component
public class OrderEventListener {

    static final String CONFIRMED = "ReservationConfirmed";

    private final ObjectMapper objectMapper;
    private final QueueRepository repository;

    public OrderEventListener(ObjectMapper objectMapper, QueueRepository repository) {
        this.objectMapper = objectMapper;
        this.repository = repository;
    }

    @KafkaListener(topics = "${curtaincall.topics.order:reservation.order}", groupId = "queue")
    void onOrderEvent(String message) {
        try {
            JsonNode root = objectMapper.readTree(message);
            if (root == null || !root.isObject()) {
                throw new InvalidSessionEventException("봉투가 JSON 객체가 아니다");
            }
            if (!CONFIRMED.equals(text(root, "eventType"))) {
                return;
            }
            JsonNode payload = root.get("payload");
            if (payload == null || !payload.isObject()) {
                throw new InvalidSessionEventException("payload가 없다");
            }
            repository.removeActive(longField(payload, "sessionId"), longField(payload, "userId"));
        } catch (JsonProcessingException e) {
            throw new InvalidSessionEventException("주문 이벤트를 읽을 수 없다", e);
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
