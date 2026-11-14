package io.github.kanabig.curtaincall.reservation.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;

import io.github.kanabig.curtaincall.reservation.InventoryIntegrationTest;
import io.github.kanabig.curtaincall.reservation.client.SeatMapEntry;
import io.github.kanabig.curtaincall.reservation.service.SeatInventoryService;
import java.util.List;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.transaction.support.TransactionTemplate;

/** [EVT-9] SessionUpdated의 가격 수정이 재고(seat_inventory)의 등급·가격에 반영되는지 확인한다. 스케줄러는 끄고 syncPrices를 직접 부른다. */
@SpringBootTest(
        properties = {
            "spring.kafka.listener.auto-startup=false",
            "curtaincall.inventory.scheduler.enabled=false",
            "curtaincall.order.scheduler.enabled=false",
            "curtaincall.outbox.relay.auto-start=false"
        })
class PriceSyncTests extends InventoryIntegrationTest {

    static final long SESSION = 1L;

    @Autowired
    SessionEventListener listener;

    @Autowired
    SeatInventoryService service;

    @Autowired
    TransactionTemplate tx;

    private long offset;

    @BeforeEach
    void readyInventory() {
        doReturn(List.of(new SeatMapEntry(1, "VIP", 150_000), new SeatMapEntry(2, "R", 110_000)))
                .when(eventClient)
                .fetchSeatMap(SESSION);
        tx.executeWithoutResult(status -> service.requestInventory(SESSION));
        assertThat(service.processPending()).isEqualTo(1);
    }

    @Test
    @DisplayName("가격을 고친 SessionUpdated를 받으면 AVAILABLE 좌석의 등급·가격이 새 값이 된다")
    void updatesAvailableSeats() {
        newPrices(200_000, 120_000);

        deliver("2026-12-01T20:00:00.000+09:00");

        assertThat(service.syncPrices()).isEqualTo(1);
        assertThat(price(1)).isEqualTo(200_000);
        assertThat(price(2)).isEqualTo(120_000);
        assertThat(service.syncPrices()).isZero();
    }

    @Test
    @DisplayName("같은 이벤트를 다시 받거나 다시 반영해도 결과가 같고 event를 또 부르지 않는다")
    void redeliveryIsIdempotent() {
        newPrices(200_000, 120_000);
        deliver("2026-12-01T20:00:00.000+09:00");
        service.syncPrices();

        deliver("2026-12-01T20:00:00.000+09:00");

        assertThat(service.syncPrices()).isZero();
        assertThat(price(1)).isEqualTo(200_000);
    }

    @Test
    @DisplayName("이벤트 순서가 뒤집혀 와도 마지막 배치 가격으로 맞는다")
    void reversedOrderConverges() {
        // 새 이벤트(20:05)가 먼저 와서 반영된 뒤 옛 이벤트(20:00)가 늦게 도착한다
        newPrices(200_000, 120_000);
        deliver("2026-12-01T20:05:00.000+09:00");
        service.syncPrices();

        deliver("2026-12-01T20:00:00.000+09:00");
        service.syncPrices();

        assertThat(price(1)).isEqualTo(200_000);
        assertThat(price(2)).isEqualTo(120_000);
    }

    @Test
    @DisplayName("AVAILABLE이 아닌 좌석은 가격을 바꾸지 않는다")
    void keepsHeldSeats() {
        jdbc.update("UPDATE seat_inventory SET status = 'HELD' WHERE session_id = ? AND seat_id = 1", SESSION);
        newPrices(200_000, 120_000);
        deliver("2026-12-01T20:00:00.000+09:00");

        service.syncPrices();

        assertThat(price(1)).isEqualTo(150_000);
        assertThat(price(2)).isEqualTo(120_000);
    }

    @Test
    @DisplayName("event 호출이 실패하면 표시가 남아 다음 주기에 다시 반영한다")
    void retriesAfterFailure() {
        doThrow(new IllegalStateException("event down")).when(eventClient).fetchSeatMap(SESSION);
        deliver("2026-12-01T20:00:00.000+09:00");

        assertThat(service.syncPrices()).isZero();

        newPrices(200_000, 120_000);
        assertThat(service.syncPrices()).isEqualTo(1);
        assertThat(price(1)).isEqualTo(200_000);
    }

    @Test
    @DisplayName("prices가 없는 SessionUpdated는 반영을 요청하지 않는다")
    void ignoresUpdateWithoutPrices() {
        listenerDeliver("2026-12-01T20:00:00.000+09:00", "{\"sessionId\":" + SESSION + "}");

        assertThat(service.syncPrices()).isZero();
    }

    private void newPrices(int vip, int r) {
        doReturn(List.of(new SeatMapEntry(1, "VIP", vip), new SeatMapEntry(2, "R", r)))
                .when(eventClient)
                .fetchSeatMap(SESSION);
    }

    private int price(long seatId) {
        return jdbc.queryForObject(
                "SELECT price FROM seat_inventory WHERE session_id = ? AND seat_id = ?",
                Integer.class,
                SESSION,
                seatId);
    }

    private void deliver(String occurredAt) {
        listenerDeliver(
                occurredAt,
                "{\"sessionId\":" + SESSION + ",\"prices\":[{\"grade\":\"VIP\",\"price\":200000},"
                        + "{\"grade\":\"R\",\"price\":120000}]}");
    }

    private void listenerDeliver(String occurredAt, String payload) {
        String value = "{\"eventId\":\"" + UUID.randomUUID() + "\",\"eventType\":\"SessionUpdated\",\"occurredAt\":\""
                + occurredAt + "\",\"aggregateId\":\"" + SESSION + "\",\"payload\":" + payload + "}";
        ConsumerRecord<String, String> record =
                new ConsumerRecord<>("event.session", 0, offset++, String.valueOf(SESSION), value);
        listener.onSessionEvent(record, org.mockito.Mockito.mock(Acknowledgment.class));
    }
}
