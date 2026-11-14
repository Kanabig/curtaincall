package io.github.kanabig.curtaincall.queue.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.kanabig.curtaincall.queue.QueueIntegrationTest;
import io.github.kanabig.curtaincall.queue.service.AdmissionService;
import io.github.kanabig.curtaincall.queue.service.QueueService;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** [QUE-6] ReservationConfirmed로 활성 목록에서 빼는 동작. */
class OrderEventListenerTests extends QueueIntegrationTest {

    @Autowired
    OrderEventListener listener;

    @Autowired
    AdmissionService admission;

    @Autowired
    QueueService queue;

    private String confirmed(long userId) {
        return envelope("ReservationConfirmed", sessionId, userId);
    }

    private static String envelope(String type, long sid, long userId) {
        return """
                {"eventId":"e-1","eventType":"%s","occurredAt":"2026-12-01T20:03:11+09:00","aggregateId":"ord_1",
                 "payload":{"reservationId":"r-1","orderId":"ord_1","userId":%d,"sessionId":%d,"seatIds":[1],"amount":1000}}""".formatted(type, userId, sid);
    }

    private boolean isActive(long userId) {
        return redis.opsForZSet().score("q:" + sessionId + ":active", String.valueOf(userId)) != null;
    }

    @Test
    @DisplayName("[QUE-6] 예매가 확정된 사용자는 활성에서 빠져 다음 대기자가 들어오고, 같은 이벤트가 다시 와도 같다")
    void removesConfirmedUser() {
        putConf(-1000, 10, 2);
        for (int u = 1; u <= 3; u++) {
            queue.enter(sessionId, u);
        }
        Instant t0 = Instant.now();
        admission.admitAll(t0);
        assertThat(isActive(1) && isActive(2)).isTrue();
        assertThat(isActive(3)).isFalse();

        listener.onOrderEvent(confirmed(1));
        listener.onOrderEvent(confirmed(1));
        assertThat(isActive(1)).isFalse();
        assertThat(isActive(2)).isTrue();

        admission.admitAll(t0.plusSeconds(1));
        assertThat(isActive(3)).isTrue();
    }

    @Test
    @DisplayName("[QUE-6] 다른 이벤트 타입은 무시하고, 읽을 수 없는 메시지는 재시도 없는 예외로 거절한다")
    void ignoresOthersAndRejectsInvalid() {
        putConf(-1000, 10, 2);
        queue.enter(sessionId, 1);
        admission.admitAll(Instant.now());

        listener.onOrderEvent(envelope("ReservationCancelled", sessionId, 1));
        assertThat(isActive(1)).isTrue();

        assertThatThrownBy(() -> listener.onOrderEvent("not json")).isInstanceOf(InvalidSessionEventException.class);
        assertThatThrownBy(() -> listener.onOrderEvent(
                        "{\"eventType\":\"ReservationConfirmed\",\"payload\":{\"userId\":\"x\",\"sessionId\":1}}"))
                .isInstanceOf(InvalidSessionEventException.class);
    }
}
