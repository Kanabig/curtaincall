package io.github.kanabig.curtaincall.reservation.service;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kanabig.curtaincall.outbox.message.EventEnvelopes;
import io.github.kanabig.curtaincall.outbox.relay.OutboxWriter;
import io.github.kanabig.curtaincall.reservation.entity.Order;
import io.github.kanabig.curtaincall.reservation.entity.OrderStatus;
import io.github.kanabig.curtaincall.reservation.entity.Reservation;
import io.github.kanabig.curtaincall.reservation.entity.ReservationStatus;
import io.github.kanabig.curtaincall.testsupport.EventContract;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;

/** reservation 서비스가 내는 이벤트·명령의 와이어 형식. 골든은 libs/test-support의 contract/. */
@JsonTest
class ReservationEventContractTest {

    @Autowired
    ObjectMapper mapper;

    private final Order order = new Order(
            "ord-1",
            "hold-1",
            42L,
            101L,
            List.of(1001L, 1002L),
            132000L,
            OrderStatus.PAYMENT_REQUESTED,
            Instant.parse("2026-12-01T11:08:11Z"),
            "card-token-1",
            "pay-1",
            1,
            null);
    private final Reservation reservation =
            new Reservation("rsv-1", "ord-1", 42L, 101L, List.of(1001L, 1002L), 132000L, ReservationStatus.CONFIRMED);

    private String envelope(String type, Object payload) {
        return new EventEnvelopes(mapper, EventContract.CLOCK).write(EventContract.EVENT_ID, type, "ord-1", payload);
    }

    private void check(String type, java.util.function.Consumer<OrderEvents> call) {
        OutboxWriter outbox = mock(OutboxWriter.class);
        call.accept(new OrderEvents(outbox));
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(outbox)
                .append(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.eq(type),
                        payload.capture());
        EventContract.assertMatches(type, envelope(type, payload.getValue()));
    }

    @Test
    @DisplayName("ApprovePayment")
    void approvePayment() {
        check("ApprovePayment", e -> e.approvePayment(order));
    }

    @Test
    @DisplayName("CancelPayment")
    void cancelPayment() {
        check("CancelPayment", e -> e.cancelPayment(order, "pay-1", "SEAT_LOST"));
    }

    @Test
    @DisplayName("ReservationConfirmed")
    void confirmed() {
        check("ReservationConfirmed", e -> e.reservationConfirmed(order, "rsv-1"));
    }

    @Test
    @DisplayName("ReservationCancelled")
    void cancelled() {
        check("ReservationCancelled", e -> e.reservationCancelled(order, reservation));
    }

    @Test
    @DisplayName("OrderRefunded")
    void refunded() {
        check("OrderRefunded", e -> e.orderRefunded(order));
    }

    @Test
    @DisplayName("SessionAvailabilityChanged")
    void availability() {
        EventContract.assertMatches(
                "SessionAvailabilityChanged",
                envelope("SessionAvailabilityChanged", SessionAvailabilityService.availabilityPayload(101L, true, 3L)));
    }
}
