package io.github.kanabig.curtaincall.event.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.kanabig.curtaincall.event.EventIntegrationTest;
import io.github.kanabig.curtaincall.event.dto.SeatSpec;
import io.github.kanabig.curtaincall.event.repository.HallSeatRepository;
import io.github.kanabig.curtaincall.event.service.HallLayoutService.Outcome;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** EVT-6: 배치 템플릿 저장. 같은 공연장은 몇 번을 만들어도, 동시에 만들어도 배치가 처음 그대로다. */
@SpringBootTest(properties = {"curtaincall.kopis.scheduler.enabled=false", "curtaincall.kopis.bootstrap=false"})
class HallLayoutServiceTests extends EventIntegrationTest {

    @Autowired
    HallLayoutService service;

    @Autowired
    HallSeatRepository repository;

    private void hall(String mt13id, Integer seatScale, Integer disabled) {
        jdbc.update(
                "INSERT INTO hall (mt13id, mt10id, name, seat_scale, disabled_seat_scale) VALUES (?, 'FC1', 'h', ?, ?)",
                mt13id,
                seatScale,
                disabled);
    }

    private int count(String table, String mt13id) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE mt13id = ?", Integer.class, mt13id);
    }

    @Test
    @DisplayName("객석 수만큼 저장하고, 저장한 배치를 그대로 읽어 온다")
    void createsAndReadsBack() {
        hall("FC000680-01", 485, 3);

        HallLayoutService.Result result = service.ensureLayout("FC000680-01");

        assertThat(result.outcome()).isEqualTo(Outcome.CREATED);
        assertThat(count("hall_seat", "FC000680-01")).isEqualTo(485);
        assertThat(repository.findSeats("FC000680-01")).hasSize(485).isEqualTo(result.seats());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM hall_seat WHERE wheelchair = TRUE", Integer.class))
                .isEqualTo(3);
    }

    @Test
    @DisplayName("두 번째 호출은 배치를 그대로 두고, 객석 수가 바뀌어도 이미 만든 배치는 변하지 않는다")
    void secondCallKeepsLayout() {
        hall("FC000680-01", 485, 3);
        List<SeatSpec> first = service.ensureLayout("FC000680-01").seats();
        List<Long> ids = jdbc.queryForList("SELECT seat_id FROM hall_seat ORDER BY seat_id", Long.class);

        HallLayoutService.Result second = service.ensureLayout("FC000680-01");
        jdbc.update("UPDATE hall SET seat_scale = 700 WHERE mt13id = 'FC000680-01'");
        HallLayoutService.Result third = service.ensureLayout("FC000680-01");

        assertThat(second.outcome()).isEqualTo(Outcome.EXISTING);
        assertThat(third.outcome()).isEqualTo(Outcome.EXISTING);
        assertThat(second.seats()).isEqualTo(first);
        assertThat(third.seats()).isEqualTo(first);
        assertThat(jdbc.queryForList("SELECT seat_id FROM hall_seat ORDER BY seat_id", Long.class))
                .isEqualTo(ids);
    }

    @Test
    @DisplayName("공연장마다 따로 만든다")
    void perHall() {
        hall("H-1", 70, null);
        hall("H-2", 126, null);

        service.ensureLayout("H-1");
        service.ensureLayout("H-2");

        assertThat(count("hall_seat", "H-1")).isEqualTo(70);
        assertThat(count("hall_seat", "H-2")).isEqualTo(126);
    }

    @Test
    @DisplayName("객석 수가 비었거나 0이거나 상한을 넘으면 배치를 만들지 않는다")
    void abnormalSeatScale() {
        hall("H-NULL", null, null);
        hall("H-ZERO", 0, null);
        hall("H-NEG", -3, null);
        hall("H-HUGE", SeatLayoutGenerator.MAX_SEATS + 1, null);
        hall("H-MAX", SeatLayoutGenerator.MAX_SEATS, null);

        assertThat(service.ensureLayout("H-NULL").outcome()).isEqualTo(Outcome.NO_SEAT_SCALE);
        assertThat(service.ensureLayout("H-ZERO").outcome()).isEqualTo(Outcome.NO_SEAT_SCALE);
        assertThat(service.ensureLayout("H-NEG").outcome()).isEqualTo(Outcome.NO_SEAT_SCALE);
        assertThat(service.ensureLayout("H-HUGE").outcome()).isEqualTo(Outcome.SEAT_SCALE_TOO_LARGE);
        assertThat(service.ensureLayout("H-NONE").outcome()).isEqualTo(Outcome.UNKNOWN_HALL);
        assertThat(service.ensureLayout("H-MAX").available()).isTrue();
        assertThat(count("hall_seat", "H-MAX")).isEqualTo(SeatLayoutGenerator.MAX_SEATS);
        assertThat(count("hall_layout", "H-NULL") + count("hall_layout", "H-ZERO") + count("hall_layout", "H-HUGE"))
                .isZero();
    }

    @Test
    @DisplayName("같은 공연장을 동시에 만들어도 좌석이 한 벌만 생기고 모두 같은 배치를 받는다")
    void concurrentCreation() throws Exception {
        hall("H-RACE", 1458, 3);
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<HallLayoutService.Result>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                Callable<HallLayoutService.Result> task = () -> {
                    start.await();
                    return service.ensureLayout("H-RACE");
                };
                futures.add(pool.submit(task));
            }
            start.countDown();
            List<SeatSpec> expected = null;
            int created = 0;
            for (Future<HallLayoutService.Result> f : futures) {
                HallLayoutService.Result r = f.get();
                created += r.outcome() == Outcome.CREATED ? 1 : 0;
                assertThat(r.seats()).hasSize(1458);
                expected = expected == null ? r.seats() : expected;
                assertThat(r.seats()).isEqualTo(expected);
            }
            assertThat(created).isEqualTo(1);
            assertThat(count("hall_seat", "H-RACE")).isEqualTo(1458);
        } finally {
            pool.shutdownNow();
        }
    }
}
