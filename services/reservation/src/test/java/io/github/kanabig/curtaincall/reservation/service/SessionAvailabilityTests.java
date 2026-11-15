package io.github.kanabig.curtaincall.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.kanabig.curtaincall.outbox.relay.OutboxWriter;
import io.github.kanabig.curtaincall.reservation.InventoryIntegrationTest;
import io.github.kanabig.curtaincall.reservation.repository.SessionAvailabilityRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

/** [QUE-8] 주기 점검으로 매진을 판정하고 바뀔 때만 이벤트를 낸다. 스케줄러는 끄고 checkAll을 직접 부른다. */
@SpringBootTest(
        properties = {
            "spring.kafka.listener.auto-startup=false",
            "curtaincall.inventory.scheduler.enabled=false",
            "curtaincall.availability.scheduler.enabled=false",
            "curtaincall.order.scheduler.enabled=false",
            "curtaincall.outbox.relay.auto-start=false"
        })
class SessionAvailabilityTests extends InventoryIntegrationTest {

    private static final long SESSION = 7L;

    @Autowired
    SessionAvailabilityService service;

    @Autowired
    SessionAvailabilityRepository repository;

    @Autowired
    OutboxWriter outbox;

    @Autowired
    TransactionTemplate tx;

    private void createSession(long sessionId, String status, Instant startAt, int seats) {
        jdbc.update(
                "INSERT INTO session_stock (session_id, status, seat_count, next_attempt_at, start_at)"
                        + " VALUES (?, ?, ?, ?, ?)",
                sessionId,
                status,
                seats,
                Timestamp.from(Instant.now()),
                startAt == null ? null : Timestamp.from(startAt));
        for (int seat = 1; seat <= seats; seat++) {
            jdbc.update(
                    "INSERT INTO seat_inventory (session_id, seat_id, status, grade, price) VALUES (?, ?, 'AVAILABLE', 'R', 1000)",
                    sessionId,
                    seat);
        }
    }

    private void setStatus(long sessionId, long seatId, String status) {
        jdbc.update(
                "UPDATE seat_inventory SET status = ? WHERE session_id = ? AND seat_id = ?", status, sessionId, seatId);
    }

    private List<Map<String, Object>> events() {
        return jdbc.queryForList("SELECT message_key, event_type, payload FROM outbox"
                + " WHERE topic = 'reservation.session' ORDER BY id");
    }

    private List<String> payloads() {
        return events().stream()
                .map(e -> e.get("payload").toString().replace(" ", ""))
                .toList();
    }

    @Test
    @DisplayName("[QUE-8] 마지막 좌석이 SOLD가 된 뒤 점검에서 매진 이벤트가 한 번 나가고, 다시 점검해도 늘지 않는다")
    void publishesSoldOutOnce() {
        createSession(SESSION, "READY", Instant.now().plusSeconds(3600), 3);
        setStatus(SESSION, 1, "SOLD");
        setStatus(SESSION, 2, "SOLD");

        assertThat(service.checkAll()).isZero();
        setStatus(SESSION, 3, "SOLD");
        assertThat(service.checkAll()).isEqualTo(1);
        assertThat(service.checkAll()).isZero();

        assertThat(events()).hasSize(1);
        assertThat(events().get(0)).containsEntry("message_key", String.valueOf(SESSION));
        assertThat(events().get(0)).containsEntry("event_type", "SessionAvailabilityChanged");
        assertThat(payloads().get(0))
                .contains("\"sessionId\":7")
                .contains("\"soldOut\":true")
                .contains("\"version\":1");
    }

    @Test
    @DisplayName("[QUE-8] 점유가 남아 있으면(만료됐어도 아직 돌아오지 않았으면) 매진이 아니다")
    void heldSeatIsNotSoldOut() {
        createSession(SESSION, "READY", Instant.now().plusSeconds(3600), 2);
        setStatus(SESSION, 1, "SOLD");
        setStatus(SESSION, 2, "HELD");

        assertThat(service.checkAll()).isZero();
        assertThat(events()).isEmpty();
    }

    @Test
    @DisplayName("[QUE-8] 취소로 좌석이 돌아오면 매진 해제 이벤트가 version 2로 나간다")
    void cancelReleasesSoldOut() {
        createSession(SESSION, "READY", Instant.now().plusSeconds(3600), 2);
        setStatus(SESSION, 1, "SOLD");
        setStatus(SESSION, 2, "SOLD");
        service.checkAll();

        setStatus(SESSION, 2, "AVAILABLE");
        assertThat(service.checkAll()).isEqualTo(1);

        assertThat(payloads()).hasSize(2);
        assertThat(payloads().get(1)).contains("\"soldOut\":false").contains("\"version\":2");
    }

    @Test
    @DisplayName("[QUE-8] 판매 중이 아닌 회차는 점검하지 않는다: 재고 생성 전, 시작 시각 이후")
    void onlySellingSessions() {
        createSession(10, "PENDING", Instant.now().plusSeconds(3600), 1);
        createSession(11, "READY", Instant.now().minusSeconds(60), 1);
        setStatus(10, 1, "SOLD");
        setStatus(11, 1, "SOLD");

        assertThat(service.checkAll()).isZero();
        assertThat(events()).isEmpty();
    }

    @Test
    @DisplayName("[QUE-8] 인스턴스 여러 개가 같은 회차를 동시에 점검해도 전환마다 이벤트는 한 번이고 version은 순서대로다")
    void multipleInstancesPublishEachTransitionOnce() throws Exception {
        createSession(SESSION, "READY", Instant.now().plusSeconds(3600), 4);
        int instances = 8;
        List<SessionAvailabilityService> services = new ArrayList<>();
        for (int i = 0; i < instances; i++) {
            services.add(new SessionAvailabilityService(repository, outbox, tx));
        }
        ExecutorService pool = Executors.newFixedThreadPool(instances);
        try {
            for (int round = 0; round < 3; round++) {
                // 매진 → 해제 → 매진 순으로 전환한다
                String status = round % 2 == 0 ? "SOLD" : "AVAILABLE";
                jdbc.update("UPDATE seat_inventory SET status = ? WHERE session_id = ?", status, SESSION);
                CountDownLatch start = new CountDownLatch(1);
                List<Future<Integer>> futures = new ArrayList<>();
                for (SessionAvailabilityService instance : services) {
                    futures.add(pool.submit(() -> {
                        start.await();
                        return instance.checkAll();
                    }));
                }
                start.countDown();
                int published = 0;
                for (Future<Integer> future : futures) {
                    published += future.get();
                }
                assertThat(published).as("round %d", round).isEqualTo(1);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(payloads()).hasSize(3);
        for (int i = 0; i < 3; i++) {
            assertThat(payloads().get(i)).contains("\"version\":" + (i + 1)).contains("\"soldOut\":" + (i % 2 == 0));
        }
    }

    @Test
    @DisplayName("[QUE-8] 마지막 두 좌석이 동시에 확정되는 동안 점검이 돌아도 매진을 놓치지 않는다")
    void lastTwoConfirmedConcurrently() throws Exception {
        for (int attempt = 0; attempt < 20; attempt++) {
            long session = 100 + attempt;
            createSession(session, "READY", Instant.now().plusSeconds(3600), 2);
            ExecutorService pool = Executors.newFixedThreadPool(3);
            AtomicBoolean confirming = new AtomicBoolean(true);
            CountDownLatch start = new CountDownLatch(1);
            try {
                Future<?> checker = pool.submit(() -> {
                    start.await();
                    while (confirming.get()) {
                        service.checkAll();
                    }
                    return null;
                });
                List<Future<?>> confirms = new ArrayList<>();
                for (long seat = 1; seat <= 2; seat++) {
                    long seatId = seat;
                    confirms.add(pool.submit(() -> {
                        start.await();
                        tx.executeWithoutResult(status -> setStatus(session, seatId, "SOLD"));
                        return null;
                    }));
                }
                start.countDown();
                for (Future<?> confirm : confirms) {
                    confirm.get();
                }
                confirming.set(false);
                checker.get();
            } finally {
                pool.shutdownNow();
            }
            // 확정이 끝난 뒤 다음 주기의 점검이 판정한다
            service.checkAll();

            Integer soldOutEvents = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM outbox WHERE topic = 'reservation.session' AND message_key = ?"
                            + " AND JSON_EXTRACT(payload, '$.payload.soldOut') = CAST('true' AS JSON)",
                    Integer.class,
                    String.valueOf(session));
            assertThat(soldOutEvents).as("attempt %d", attempt).isEqualTo(1);
        }
    }
}
