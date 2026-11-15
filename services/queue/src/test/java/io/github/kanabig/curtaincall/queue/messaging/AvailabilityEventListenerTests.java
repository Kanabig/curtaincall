package io.github.kanabig.curtaincall.queue.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.kanabig.curtaincall.queue.QueueIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** [QUE-8] reservation.session 메시지를 읽어 conf에 반영한다. 리스너 메서드를 직접 부른다. */
class AvailabilityEventListenerTests extends QueueIntegrationTest {

    @Autowired
    AvailabilityEventListener listener;

    private String message(String type, String payload) {
        return """
                {"eventId":"%s","eventType":"%s","occurredAt":"2026-11-15T10:00:00.000+09:00","aggregateId":"%d","payload":%s}""".formatted(UUID.randomUUID(), type, sessionId, payload);
    }

    private String changed(boolean soldOut, long version) {
        return message(
                "SessionAvailabilityChanged",
                "{\"sessionId\":%d,\"soldOut\":%s,\"version\":%d}".formatted(sessionId, soldOut, version));
    }

    private Object field(String name) {
        return redis.opsForHash().get("q:" + sessionId + ":conf", name);
    }

    @Test
    @DisplayName("[QUE-8] 매진 이벤트를 반영하고, 역순·중복 전달에도 마지막 version이 남는다")
    void appliesAndConverges() {
        listener.onAvailabilityEvent(changed(false, 2));
        listener.onAvailabilityEvent(changed(true, 1));
        listener.onAvailabilityEvent(changed(false, 2));

        assertThat(field("soldOut")).isEqualTo("0");
        assertThat(field("soldOutVer")).isEqualTo("2");
    }

    @Test
    @DisplayName("[QUE-8] 다른 이벤트 타입은 무시하고, 읽을 수 없는 메시지는 DLT 대상 예외로 던진다")
    void ignoresOthersAndRejectsInvalid() {
        listener.onAvailabilityEvent(message("SomethingElse", "{}"));
        assertThat(field("soldOut")).isNull();

        assertThatThrownBy(() -> listener.onAvailabilityEvent("{not-json"))
                .isInstanceOf(InvalidSessionEventException.class);
        assertThatThrownBy(() -> listener.onAvailabilityEvent(
                        message("SessionAvailabilityChanged", "{\"sessionId\":1,\"soldOut\":\"yes\",\"version\":1}")))
                .isInstanceOf(InvalidSessionEventException.class);
    }
}
