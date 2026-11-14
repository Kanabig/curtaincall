package io.github.kanabig.curtaincall.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.github.kanabig.curtaincall.common.web.error.BusinessException;
import io.github.kanabig.curtaincall.reservation.InventoryIntegrationTest;
import io.github.kanabig.curtaincall.reservation.client.SeatMapEntry;
import io.github.kanabig.curtaincall.reservation.error.ReservationErrorCode;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

/** EVT-9 회차별 좌석 재고 생성. 스케줄러는 끄고 processPending을 직접 호출한다. */
@SpringBootTest(
        properties = {
            "spring.kafka.listener.auto-startup=false",
            "curtaincall.inventory.scheduler.enabled=false",
            "curtaincall.order.scheduler.enabled=false",
            "curtaincall.outbox.relay.auto-start=false",
            "curtaincall.inventory.retry-backoff=30s"
        })
class SeatInventoryServiceTests extends InventoryIntegrationTest {

    @Autowired
    SeatInventoryService service;

    @Autowired
    TransactionTemplate tx;

    @Test
    @DisplayName("배치대로 재고를 만들고 등급·가격을 복사하며 회차를 READY로 바꾼다")
    void createsInventoryFromSeatMap() {
        doReturn(seatMap(10_000)).when(eventClient).fetchSeatMap(1L);
        tx.executeWithoutResult(status -> service.requestInventory(1L));

        assertThat(service.processPending()).isEqualTo(1);

        assertThat(inventoryCount(1L)).isEqualTo(10_000);
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM seat_inventory WHERE session_id = 1 AND status = 'AVAILABLE' AND version = 0",
                        Integer.class))
                .isEqualTo(10_000);
        assertThat(jdbc.queryForObject(
                        "SELECT price FROM seat_inventory WHERE session_id = 1 AND seat_id = 1", Integer.class))
                .isEqualTo(150_000);
        assertThat(jdbc.queryForObject(
                        "SELECT CONCAT(status, ':', seat_count) FROM session_stock WHERE session_id = 1", String.class))
                .isEqualTo("READY:10000");
    }

    @Test
    @DisplayName("같은 회차를 다시 요청해도 재고 수는 같고 event를 다시 부르지 않으며 먼저 만든 행을 덮어쓰지 않는다")
    void sameSessionTwiceKeepsCount() {
        doReturn(seatMap(2_500)).when(eventClient).fetchSeatMap(2L);
        tx.executeWithoutResult(status -> service.requestInventory(2L));
        service.processPending();
        jdbc.update("UPDATE seat_inventory SET status = 'HELD' WHERE session_id = 2 AND seat_id = 7");

        tx.executeWithoutResult(status -> service.requestInventory(2L));
        assertThat(service.processPending()).isZero();

        assertThat(inventoryCount(2L)).isEqualTo(2_500);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM session_stock WHERE session_id = 2", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "SELECT status FROM seat_inventory WHERE session_id = 2 AND seat_id = 7", String.class))
                .isEqualTo("HELD");
        verify(eventClient, times(1)).fetchSeatMap(2L);
    }

    @Test
    @DisplayName("재고 행이 일부 남아 있어도(중간 실패 후 재처리) 중복 없이 나머지를 채운다")
    void fillsRemainingSeats() {
        doReturn(seatMap(2_345)).when(eventClient).fetchSeatMap(3L);
        jdbc.update("INSERT INTO seat_inventory (session_id, seat_id, grade, price) VALUES (3, 1, 'VIP', 150000)");
        tx.executeWithoutResult(status -> service.requestInventory(3L));

        service.processPending();

        assertThat(inventoryCount(3L)).isEqualTo(2_345);
    }

    @Test
    @DisplayName("재고가 PENDING이거나 없는 회차는 점유 전 확인에서 거절하고 READY가 되면 통과한다")
    void rejectsUntilReady() {
        assertRejected(99L);
        tx.executeWithoutResult(status -> service.requestInventory(99L));
        assertRejected(99L);

        doReturn(seatMap(10)).when(eventClient).fetchSeatMap(99L);
        service.processPending();

        assertThatCode(() -> service.requireReady(99L)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("event 호출이 실패하거나 배치가 비어 있으면 재고 없이 PENDING으로 남고 백오프 뒤로 미뤄진다")
    void failureStaysPendingWithBackoff() {
        doThrow(new IllegalStateException("event 중단")).when(eventClient).fetchSeatMap(4L);
        doReturn(List.of()).when(eventClient).fetchSeatMap(5L);
        tx.executeWithoutResult(status -> {
            service.requestInventory(4L);
            service.requestInventory(5L);
        });

        assertThat(service.processPending()).isZero();

        for (long sessionId : new long[] {4L, 5L}) {
            assertThat(inventoryCount(sessionId)).isZero();
            assertThat(jdbc.queryForObject(
                            "SELECT status FROM session_stock WHERE session_id = ?", String.class, sessionId))
                    .isEqualTo("PENDING");
            assertThat(jdbc.queryForObject(
                            "SELECT attempts FROM session_stock WHERE session_id = ?", Integer.class, sessionId))
                    .isEqualTo(1);
            assertThat(jdbc.queryForObject(
                            "SELECT next_attempt_at > NOW(6) FROM session_stock WHERE session_id = ?",
                            Boolean.class,
                            sessionId))
                    .isTrue();
        }
        // 백오프가 지나기 전에는 다시 부르지 않는다
        service.processPending();
        verify(eventClient, times(1)).fetchSeatMap(4L);
    }

    @Test
    @DisplayName("event가 복구되면 다음 처리에서 READY가 된다")
    void recoversAfterEventComesBack() {
        doThrow(new IllegalStateException("event 중단"))
                .doReturn(seatMap(20))
                .when(eventClient)
                .fetchSeatMap(7L);
        tx.executeWithoutResult(status -> service.requestInventory(7L));
        service.processPending();
        jdbc.update("UPDATE session_stock SET next_attempt_at = NOW(6) - INTERVAL 1 SECOND WHERE session_id = 7");

        assertThat(service.processPending()).isEqualTo(1);

        assertThat(inventoryCount(7L)).isEqualTo(20);
        assertThatCode(() -> service.requireReady(7L)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("등급이 너무 길면 잘라 넣지 않고 롤백한 채 PENDING으로 남는다")
    void invalidDataIsNotTruncated() {
        doReturn(List.of(new SeatMapEntry(1, "R", 110_000), new SeatMapEntry(2, "가".repeat(40), 110_000)))
                .when(eventClient)
                .fetchSeatMap(6L);
        tx.executeWithoutResult(status -> service.requestInventory(6L));

        assertThat(service.processPending()).isZero();

        assertThat(inventoryCount(6L)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM session_stock WHERE session_id = 6", String.class))
                .isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT last_error FROM session_stock WHERE session_id = 6", String.class))
                .isNotBlank();
    }

    @Test
    @DisplayName("두 인스턴스가 동시에 처리해도 한 회차의 배치는 한 번만 받는다")
    void concurrentRunsClaimOnce() throws Exception {
        CountDownLatch inCall = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
                    inCall.countDown();
                    release.await();
                    return seatMap(100);
                })
                .when(eventClient)
                .fetchSeatMap(8L);
        tx.executeWithoutResult(status -> service.requestInventory(8L));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> first = pool.submit(service::processPending);
            inCall.await();
            // 첫 호출이 event 응답을 기다리는 동안 두 번째가 같은 회차를 가져가면 안 된다
            Future<Integer> second = pool.submit(service::processPending);
            assertThat(second.get()).isZero();
            release.countDown();
            assertThat(first.get()).isEqualTo(1);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }

        verify(eventClient, times(1)).fetchSeatMap(8L);
        assertThat(inventoryCount(8L)).isEqualTo(100);
    }

    private void assertRejected(long sessionId) {
        assertThatThrownBy(() -> service.requireReady(sessionId))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ReservationErrorCode.SESSION_NOT_ON_SALE));
    }
}
