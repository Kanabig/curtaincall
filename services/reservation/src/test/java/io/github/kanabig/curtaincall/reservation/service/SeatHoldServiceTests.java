package io.github.kanabig.curtaincall.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.kanabig.curtaincall.common.web.error.BusinessException;
import io.github.kanabig.curtaincall.reservation.InventoryIntegrationTest;
import io.github.kanabig.curtaincall.reservation.entity.SeatHold;
import io.github.kanabig.curtaincall.reservation.error.ReservationErrorCode;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** RSV-1~4 좌석 점유. 동시성 테스트는 반복 실행해 전부 통과해야 하고, 교착과 락 대기 타임아웃은 InnoDB 지표로 센다. */
@SpringBootTest(
        properties = {"spring.kafka.listener.auto-startup=false", "curtaincall.inventory.scheduler.enabled=false"})
class SeatHoldServiceTests extends InventoryIntegrationTest {

    static final int REPEAT = 30;
    static final long SESSION = 1L;

    @Autowired
    SeatHoldService service;

    void seed(long sessionId, int seats) {
        jdbc.update(
                "INSERT INTO session_stock (session_id, status, seat_count, next_attempt_at) VALUES (?, 'READY', ?, NOW(6))",
                sessionId,
                seats);
        for (int id = 1; id <= seats; id++) {
            jdbc.update(
                    "INSERT INTO seat_inventory (session_id, seat_id, grade, price) VALUES (?, ?, 'R', 110000)",
                    sessionId,
                    id);
        }
    }

    /**
     * InnoDB 카운터(lock_deadlocks, lock_timeouts). INNODB_METRICS는 PROCESS 권한이 필요해 테스트 컨테이너의 root로 읽는다. 컨테이너는 테스트가 공유하지만 이 테스트가 도는
     * 동안 다른 동시성 테스트는 돌지 않으므로 전후 차이를 본다.
     */
    long innodbMetric(String name) throws Exception {
        String url;
        try (Connection app = jdbc.getDataSource().getConnection()) {
            url = app.getMetaData().getURL();
        }
        try (Connection root = DriverManager.getConnection(url, "root", "test");
                PreparedStatement ps =
                        root.prepareStatement("SELECT `COUNT` FROM information_schema.INNODB_METRICS WHERE NAME = ?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** 모든 작업을 한 번에 출발시키고 결과를 모은다. 예외도 결과로 돌려 보낸다. */
    <T> List<Object> runConcurrently(List<Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        try {
            CountDownLatch ready = new CountDownLatch(tasks.size());
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    try {
                        return task.call();
                    } catch (Exception e) {
                        return e;
                    }
                }));
            }
            ready.await();
            go.countDown();
            List<Object> results = new ArrayList<>();
            for (Future<Object> future : futures) {
                results.add(future.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    static boolean isSeatConflict(Object result) {
        return result instanceof BusinessException e && e.errorCode() == ReservationErrorCode.SEAT_ALREADY_HELD;
    }

    static boolean isLimit(Object result) {
        return result instanceof BusinessException e && e.errorCode() == ReservationErrorCode.PURCHASE_LIMIT_EXCEEDED;
    }

    @Test
    @DisplayName("점유하면 좌석이 HELD가 되고 TTL 5분의 만료 시각이 붙는다")
    void holdsSeats() {
        seed(SESSION, 10);

        SeatHold hold = service.hold(SESSION, 100L, List.of(3L, 1L));

        assertThat(hold.seatIds()).containsExactly(1L, 3L);
        assertThat(hold.expiresAt())
                .isBetween(
                        java.time.Instant.now().plusSeconds(290),
                        java.time.Instant.now().plusSeconds(301));
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM seat_inventory WHERE session_id = ? AND status = 'HELD' AND hold_id = ? AND holder_id = 100",
                        Integer.class,
                        SESSION,
                        hold.holdId()))
                .isEqualTo(2);
    }

    @Test
    @DisplayName("재고가 준비되지 않은 회차는 점유를 거절한다")
    void rejectsSessionThatIsNotReady() {
        jdbc.update("INSERT INTO session_stock (session_id, status, next_attempt_at) VALUES (9, 'PENDING', NOW(6))");

        assertThatThrownBy(() -> service.hold(9L, 100L, List.of(1L)))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ReservationErrorCode.SESSION_NOT_ON_SALE));
        assertThatThrownBy(() -> service.hold(404L, 100L, List.of(1L))).isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("일부 좌석만 막혀도 전부 실패하고 나머지 좌석은 점유되지 않는다")
    void allOrNothing() {
        seed(SESSION, 10);
        service.hold(SESSION, 100L, List.of(2L));

        assertThatThrownBy(() -> service.hold(SESSION, 200L, List.of(1L, 2L, 3L)))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ReservationErrorCode.SEAT_ALREADY_HELD);
                    assertThat(e.properties()).containsEntry("seatIds", List.of(2L));
                });

        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM seat_inventory WHERE session_id = ? AND holder_id = 200",
                        Integer.class,
                        SESSION))
                .isZero();
    }

    @Test
    @DisplayName("점유 시간이 지난 좌석은 정리 전에도 다른 사용자가 점유할 수 있다")
    void expiredHoldCanBeTakenBeforeCleanup() {
        seed(SESSION, 10);
        SeatHold first = service.hold(SESSION, 100L, List.of(1L, 2L));
        jdbc.update(
                "UPDATE seat_inventory SET expires_at = NOW(6) - INTERVAL 1 SECOND WHERE hold_id = ?", first.holdId());

        SeatHold second = service.hold(SESSION, 200L, List.of(2L, 3L));

        assertThat(second.seatIds()).containsExactly(2L, 3L);
        assertThat(jdbc.queryForObject(
                        "SELECT holder_id FROM seat_inventory WHERE session_id = ? AND seat_id = 2",
                        Long.class,
                        SESSION))
                .isEqualTo(200L);
        // 만료된 점유는 한도에도 세지 않는다: 100번 사용자는 다시 4매를 잡을 수 있다
        assertThat(service.hold(SESSION, 100L, List.of(4L, 5L, 6L, 7L)).seatIds())
                .hasSize(4);
    }

    @Test
    @DisplayName("점유를 풀면 좌석이 다시 비고, 남의 점유나 이미 푼 점유는 404다")
    void releaseHold() {
        seed(SESSION, 10);
        SeatHold hold = service.hold(SESSION, 100L, List.of(1L, 2L));

        assertThatThrownBy(() -> service.release(hold.holdId(), 999L)).isInstanceOf(BusinessException.class);
        service.release(hold.holdId(), 100L);

        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM seat_inventory WHERE session_id = ? AND status = 'AVAILABLE'",
                        Integer.class,
                        SESSION))
                .isEqualTo(10);
        assertThatThrownBy(() -> service.release(hold.holdId(), 100L))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ReservationErrorCode.HOLD_NOT_FOUND));
        assertThat(service.hold(SESSION, 200L, List.of(1L)).seatIds()).containsExactly(1L);
    }

    @Test
    @DisplayName("확정된 좌석은 한도에 합산되고 점유할 수 없다")
    void soldSeatsCountTowardLimit() {
        seed(SESSION, 10);
        jdbc.update(
                "UPDATE seat_inventory SET status = 'SOLD', hold_id = UUID(), holder_id = 100 WHERE session_id = ? AND seat_id IN (1, 2, 3)",
                SESSION);

        assertThatThrownBy(() -> service.hold(SESSION, 100L, List.of(4L, 5L)))
                .satisfies(e -> assertThat(isLimit(e)).isTrue());
        assertThat(service.hold(SESSION, 100L, List.of(4L)).seatIds()).containsExactly(4L);
        assertThatThrownBy(() -> service.hold(SESSION, 200L, List.of(1L)))
                .satisfies(e -> assertThat(isSeatConflict(e)).isTrue());
    }

    @Test
    @DisplayName("한 번에 4매를 넘기거나 중복·빈 요청은 거절한다")
    void validatesRequest() {
        seed(SESSION, 10);

        assertThatThrownBy(() -> service.hold(SESSION, 100L, List.of(1L, 2L, 3L, 4L, 5L)))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.hold(SESSION, 100L, List.of(1L, 1L))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.hold(SESSION, 100L, List.of())).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.hold(SESSION, 100L, List.of(1L, 99L)))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        e -> assertThat(e.properties()).containsEntry("seatIds", List.of(99L)));
    }

    @RepeatedTest(REPEAT)
    @DisplayName("같은 좌석을 50명이 동시에 점유하면 성공은 1건이다")
    void sameSeatManyUsersOneWinner() throws Exception {
        seed(SESSION, 10);
        long deadlocks = innodbMetric("lock_deadlocks");
        long timeouts = innodbMetric("lock_timeouts");

        List<Callable<SeatHold>> tasks = IntStream.rangeClosed(1, 50)
                .<Callable<SeatHold>>mapToObj(u -> () -> service.hold(SESSION, u, List.of(5L)))
                .toList();
        List<Object> results = runConcurrently(tasks);

        assertThat(results.stream().filter(r -> r instanceof SeatHold)).hasSize(1);
        assertThat(results.stream().filter(SeatHoldServiceTests::isSeatConflict))
                .hasSize(49);
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM seat_inventory WHERE session_id = ? AND status = 'HELD'",
                        Integer.class,
                        SESSION))
                .isEqualTo(1);
        assertThat(innodbMetric("lock_deadlocks") - deadlocks).isZero();
        assertThat(innodbMetric("lock_timeouts") - timeouts).isZero();
    }

    @RepeatedTest(REPEAT)
    @DisplayName("겹치는 좌석 집합을 서로 다른 순서로 요청해도 교착이 없고 좌석이 이중 점유되지 않는다")
    void overlappingSetsInDifferentOrders() throws Exception {
        seed(SESSION, 12);
        long deadlocks = innodbMetric("lock_deadlocks");
        long timeouts = innodbMetric("lock_timeouts");

        // 사용자마다 같은 좌석 풀(1~8)에서 서로 다른 순서로 4개를 고른다
        List<Callable<SeatHold>> tasks = new ArrayList<>();
        for (int u = 1; u <= 40; u++) {
            long user = u;
            List<Long> seats = new ArrayList<>(List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L));
            Collections.shuffle(seats, new java.util.Random(u));
            List<Long> pick = seats.subList(0, 4);
            tasks.add(() -> service.hold(SESSION, user, pick));
        }
        List<Object> results = runConcurrently(tasks);

        // 성공한 점유끼리 좌석이 겹치지 않고, 실패는 SEAT_ALREADY_HELD뿐이다
        List<Long> held = new ArrayList<>();
        for (Object result : results) {
            if (result instanceof SeatHold hold) {
                held.addAll(hold.seatIds());
            } else {
                assertThat(isSeatConflict(result)).as("실패 원인: %s", result).isTrue();
            }
        }
        assertThat(held).doesNotHaveDuplicates();
        assertThat(held).hasSizeGreaterThanOrEqualTo(4);
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM seat_inventory WHERE session_id = ? AND status = 'HELD'",
                        Integer.class,
                        SESSION))
                .isEqualTo(held.size());
        assertThat(innodbMetric("lock_deadlocks") - deadlocks).isZero();
        assertThat(innodbMetric("lock_timeouts") - timeouts).isZero();
    }

    @RepeatedTest(REPEAT)
    @DisplayName("같은 사용자가 탭 10개로 동시에 요청해도 보유 좌석은 4매를 넘지 않는다")
    void sameUserManyTabsLimit() throws Exception {
        seed(SESSION, 40);
        long deadlocks = innodbMetric("lock_deadlocks");
        long timeouts = innodbMetric("lock_timeouts");

        List<Callable<SeatHold>> tasks = new ArrayList<>();
        for (int tab = 0; tab < 10; tab++) {
            long first = tab * 3 + 1;
            tasks.add(() -> service.hold(SESSION, 77L, List.of(first, first + 1)));
        }
        List<Object> results = runConcurrently(tasks);

        assertThat(results.stream().filter(r -> r instanceof SeatHold)).hasSize(2);
        assertThat(results.stream().filter(r -> !(r instanceof SeatHold)).allMatch(SeatHoldServiceTests::isLimit))
                .isTrue();
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM seat_inventory WHERE session_id = ? AND holder_id = 77",
                        Integer.class,
                        SESSION))
                .isEqualTo(4);
        assertThat(innodbMetric("lock_deadlocks") - deadlocks).isZero();
        assertThat(innodbMetric("lock_timeouts") - timeouts).isZero();
    }

    @RepeatedTest(REPEAT)
    @DisplayName("한도 행이 아직 없는 사용자가 처음부터 동시에 요청해도 교착이 없다")
    void firstRequestsRaceOnMissingQuotaRow() throws Exception {
        seed(SESSION, 40);
        long deadlocks = innodbMetric("lock_deadlocks");

        // 사용자 20명이 각각 탭 3개로 동시에 시작한다. 한도 행은 아무도 만들어 두지 않았다
        List<Callable<SeatHold>> tasks = new ArrayList<>();
        for (int user = 1; user <= 20; user++) {
            for (int tab = 0; tab < 3; tab++) {
                long u = user;
                long seat = (user + tab * 7L) % 40 + 1;
                tasks.add(() -> service.hold(SESSION, u, List.of(seat)));
            }
        }
        List<Object> results = runConcurrently(tasks);

        results.stream()
                .filter(r -> !(r instanceof SeatHold))
                .forEach(r -> assertThat(isSeatConflict(r)).as("실패 원인: %s", r).isTrue());
        assertThat(innodbMetric("lock_deadlocks") - deadlocks).isZero();
    }
}
