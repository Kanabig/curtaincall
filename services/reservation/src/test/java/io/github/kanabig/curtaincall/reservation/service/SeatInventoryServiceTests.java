package io.github.kanabig.curtaincall.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

import io.github.kanabig.curtaincall.common.web.error.BusinessException;
import io.github.kanabig.curtaincall.reservation.InventoryIntegrationTest;
import io.github.kanabig.curtaincall.reservation.error.ReservationErrorCode;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

/** EVT-9 회차별 좌석 재고 생성. */
@SpringBootTest(properties = "spring.kafka.listener.auto-startup=false")
class SeatInventoryServiceTests extends InventoryIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(SeatInventoryServiceTests.class);

    @Autowired
    SeatInventoryService service;

    @Autowired
    TransactionTemplate tx;

    @Test
    @DisplayName("배치대로 재고를 만들고 등급·가격을 복사하며 회차를 준비 완료로 표시한다")
    void createsInventoryFromSeatMap() {
        when(eventClient.fetchSeatMap(1L)).thenReturn(seatMap(10_000));

        long started = System.nanoTime();
        tx.executeWithoutResult(status -> service.createInventory(1L));
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;

        assertThat(inventoryCount(1L)).isEqualTo(10_000);
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM seat_inventory WHERE session_id = 1 AND status = 'AVAILABLE' AND version = 0",
                        Integer.class))
                .isEqualTo(10_000);
        assertThat(jdbc.queryForObject(
                        "SELECT price FROM seat_inventory WHERE session_id = 1 AND seat_id = 1", Integer.class))
                .isEqualTo(150_000);
        assertThat(jdbc.queryForObject("SELECT seat_count FROM session_stock WHERE session_id = 1", Integer.class))
                .isEqualTo(10_000);
        // 정식 측정이 아니다. 완료 보고용으로 로그에만 남긴다
        log.info("[inventory-timing] seats=10000 elapsedMs={}", elapsedMs);
    }

    @Test
    @DisplayName("같은 회차를 다시 만들어도 재고 수는 같고 먼저 만든 행을 덮어쓰지 않는다")
    void sameSessionTwiceKeepsCount() {
        when(eventClient.fetchSeatMap(2L)).thenReturn(seatMap(2_500));
        tx.executeWithoutResult(status -> service.createInventory(2L));
        jdbc.update("UPDATE seat_inventory SET status = 'HELD' WHERE session_id = 2 AND seat_id = 7");

        tx.executeWithoutResult(status -> service.createInventory(2L));

        assertThat(inventoryCount(2L)).isEqualTo(2_500);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM session_stock WHERE session_id = 2", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "SELECT status FROM seat_inventory WHERE session_id = 2 AND seat_id = 7", String.class))
                .isEqualTo("HELD");
    }

    @Test
    @DisplayName("배치 크기로 나누어 넣어도 마지막 남은 좌석까지 들어간다")
    void handlesPartialLastChunk() {
        when(eventClient.fetchSeatMap(3L)).thenReturn(seatMap(2_345));

        tx.executeWithoutResult(status -> service.createInventory(3L));

        assertThat(inventoryCount(3L)).isEqualTo(2_345);
    }

    @Test
    @DisplayName("재고가 준비되지 않은 회차는 점유 전 확인에서 거절한다")
    void rejectsSessionWithoutInventory() {
        assertThatThrownBy(() -> service.requireReady(99L))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ReservationErrorCode.SESSION_NOT_ON_SALE));

        when(eventClient.fetchSeatMap(99L)).thenReturn(seatMap(10));
        tx.executeWithoutResult(status -> service.createInventory(99L));

        assertThatCode(() -> service.requireReady(99L)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("event 호출이 실패하거나 배치가 비어 있으면 아무것도 남지 않는다")
    void failureLeavesNothing() {
        doThrow(new IllegalStateException("event 중단")).when(eventClient).fetchSeatMap(4L);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> service.createInventory(4L)))
                .isInstanceOf(IllegalStateException.class);

        doReturn(List.of()).when(eventClient).fetchSeatMap(5L);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> service.createInventory(5L)))
                .isInstanceOf(IllegalStateException.class);

        assertThat(inventoryCount(4L)).isZero();
        assertThat(inventoryCount(5L)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM session_stock", Integer.class))
                .isZero();
    }
}
