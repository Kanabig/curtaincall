package io.github.kanabig.curtaincall.reservation;

import io.github.kanabig.curtaincall.reservation.client.EventClient;
import io.github.kanabig.curtaincall.reservation.client.SeatMapEntry;
import io.github.kanabig.curtaincall.testsupport.TestContainersConfig;
import java.util.List;
import java.util.stream.LongStream;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 컨테이너를 테스트 JVM에서 공유하므로 테스트마다 테이블을 비운다. event 호출은 목으로 대체한다.
 *
 * <p>컨텍스트가 여러 개 캐시되면 리스너가 같은 컨슈머 그룹으로 파티션을 나눠 가지고, 스케줄러가 서로의 PENDING 행을 가져간다. 그래서 하위 테스트는 {@code
 * spring.kafka.listener.auto-startup}과 {@code curtaincall.inventory.scheduler.enabled}를 필요한 것만 켠다.
 */
@Import(TestContainersConfig.class)
public abstract class InventoryIntegrationTest {

    @Autowired
    protected JdbcTemplate jdbc;

    @MockitoBean
    protected EventClient eventClient;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM seat_inventory");
        jdbc.update("DELETE FROM session_stock");
        jdbc.update("DELETE FROM user_session_quota");
        jdbc.update("DELETE FROM processed_event");
        jdbc.update("DELETE FROM outbox");
        jdbc.update("DELETE FROM reservation");
        jdbc.update("DELETE FROM orders");
        jdbc.update("DELETE FROM idempotency_key");
    }

    protected static List<SeatMapEntry> seatMap(int count) {
        return LongStream.rangeClosed(1, count)
                .mapToObj(id ->
                        new SeatMapEntry(id, id <= count / 10 ? "VIP" : "R", id <= count / 10 ? 150_000 : 110_000))
                .toList();
    }

    protected int inventoryCount(long sessionId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM seat_inventory WHERE session_id = ?", Integer.class, sessionId);
    }
}
