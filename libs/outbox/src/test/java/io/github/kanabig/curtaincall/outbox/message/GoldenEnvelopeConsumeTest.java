package io.github.kanabig.curtaincall.outbox.message;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.kanabig.curtaincall.testsupport.EventContract;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 골든 봉투(이미 발행된 형식)를 소비 측이 쓰는 방식 그대로 읽을 수 있는지 확인한다. */
class GoldenEnvelopeConsumeTest {

    static final List<String> NAMES = List.of(
            "SessionCreated",
            "SessionUpdated",
            "SessionSuspended",
            "SessionDeleted",
            "ApprovePayment",
            "CancelPayment",
            "PaymentApproved",
            "PaymentFailed",
            "PaymentCancelled",
            "ReservationConfirmed",
            "OrderRefunded",
            "ReservationCancelled",
            "SessionAvailabilityChanged");

    private final EventEnvelopes envelopes = new EventEnvelopes(new ObjectMapper(), Clock.systemUTC());

    static List<String> names() {
        return NAMES;
    }

    @ParameterizedTest
    @MethodSource("names")
    void envelopeFields(String name) {
        EventEnvelope envelope = envelopes.read(EventContract.golden(name));

        assertThat(envelope.eventType()).isEqualTo(name);
        assertThat(envelope.eventId()).isEqualTo(EventContract.EVENT_ID);
        assertThat(envelope.occurredAt()).isEqualTo("2026-12-01T20:03:11.120+09:00");
        assertThat(OffsetDateTime.parse(envelope.occurredAt())).isNotNull();
        assertThat(envelope.payload().isObject()).isTrue();
    }

    @ParameterizedTest
    @MethodSource("names")
    void payloadTypesConsumersRely(String name) {
        JsonNode p = envelopes.read(EventContract.golden(name)).payload();
        switch (name) {
            case "SessionCreated", "SessionUpdated", "SessionSuspended" -> {
                assertThat(p.get("sessionId").isIntegralNumber()).isTrue();
                assertThat(p.get("venueId").isTextual()).isTrue();
                assertThat(p.get("admissionPerSecond").isInt()).isTrue();
                assertThat(p.get("maxActiveUsers").isInt()).isTrue();
                assertThat(p.get("prices").isArray()).isTrue();
                assertThat(p.get("prices").get(0).get("price").isInt()).isTrue();
                assertThat(OffsetDateTime.parse(p.get("startAt").asText())).isNotNull();
                assertThat(OffsetDateTime.parse(p.get("saleOpenAt").asText())).isNotNull();
            }
            case "SessionDeleted" -> assertThat(p.get("sessionId").asLong()).isEqualTo(101L);
            case "ReservationConfirmed", "ReservationCancelled" -> {
                assertThat(p.get("userId").isIntegralNumber()).isTrue();
                assertThat(p.get("seatIds").isArray()).isTrue();
                assertThat(p.get("seatIds").get(0).isIntegralNumber()).isTrue();
                assertThat(p.get("amount").asLong()).isEqualTo(132000L);
                assertThat(p.get("reservationId").isTextual()).isTrue();
            }
            case "OrderRefunded" ->
                assertThat(p.get("userId").isIntegralNumber()).isTrue();
            case "SessionAvailabilityChanged" -> {
                assertThat(p.get("soldOut").isBoolean()).isTrue();
                assertThat(p.get("version").isIntegralNumber()).isTrue();
            }
            case "PaymentApproved" -> {
                assertThat(p.get("amount").isIntegralNumber()).isTrue();
                assertThat(java.time.Instant.parse(p.get("approvedAt").asText()))
                        .isNotNull();
            }
            case "ApprovePayment" -> {
                assertThat(p.get("userId").isTextual()).isTrue();
                assertThat(p.get("cardToken").isTextual()).isTrue();
            }
            case "PaymentFailed", "PaymentCancelled", "CancelPayment" ->
                assertThat(p.get("reason").isTextual()).isTrue();
            default -> throw new IllegalStateException(name);
        }
    }
}
