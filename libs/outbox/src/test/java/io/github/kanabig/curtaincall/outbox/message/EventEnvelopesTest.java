package io.github.kanabig.curtaincall.outbox.message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class EventEnvelopesTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-12-01T11:03:11.120Z"), ZoneId.of("Asia/Seoul"));
    private final EventEnvelopes envelopes = new EventEnvelopes(new ObjectMapper(), clock);

    @Test
    @DisplayName("봉투를 쓰고 다시 읽으면 필드가 같다")
    void roundTrip() {
        String json = envelopes.write("e-1", "PaymentApproved", "ord-1", Map.of("amount", 1000));

        EventEnvelope envelope = envelopes.read(json);

        assertThat(envelope.eventId()).isEqualTo("e-1");
        assertThat(envelope.eventType()).isEqualTo("PaymentApproved");
        assertThat(envelope.aggregateId()).isEqualTo("ord-1");
        assertThat(envelope.occurredAt()).isEqualTo("2026-12-01T20:03:11.120+09:00");
        assertThat(envelope.payload().get("amount").asInt()).isEqualTo(1000);
    }

    @Test
    @DisplayName("모르는 필드는 무시한다")
    void ignoresUnknownFields() {
        String json = "{\"eventId\":\"e\",\"eventType\":\"T\",\"occurredAt\":\"t\",\"aggregateId\":\"a\","
                + "\"payload\":{},\"added\":\"later\"}";

        assertThat(envelopes.read(json).eventId()).isEqualTo("e");
    }

    @Test
    @DisplayName("JSON이 아니거나 필수 필드가 없으면 EventDeserializationException")
    void unreadable() {
        assertThatThrownBy(() -> envelopes.read("not json")).isInstanceOf(EventDeserializationException.class);
        assertThatThrownBy(() -> envelopes.read("[1]")).isInstanceOf(EventDeserializationException.class);
        assertThatThrownBy(() -> envelopes.read("{\"eventType\":\"T\"}"))
                .isInstanceOf(EventDeserializationException.class);
        assertThatThrownBy(() -> envelopes.read(null)).isInstanceOf(EventDeserializationException.class);
    }
}
