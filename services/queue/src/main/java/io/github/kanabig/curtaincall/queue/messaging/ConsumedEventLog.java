package io.github.kanabig.curtaincall.queue.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 소비 완료 한 줄. 다른 서비스의 처리 완료 로그와 같은 로거 이름이라 {@code logging.level.curtaincall.consumer}로 함께 끈다. */
final class ConsumedEventLog {

    private static final Logger log = LoggerFactory.getLogger("curtaincall.consumer");

    private ConsumedEventLog() {}

    static void processed(JsonNode root) {
        log.info("이벤트 처리 완료: eventId={} type={}", field(root, "eventId"), field(root, "eventType"));
    }

    private static String field(JsonNode root, String name) {
        JsonNode value = root.get(name);
        return value != null && value.isTextual() ? value.asText() : "-";
    }
}
