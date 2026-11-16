package io.github.kanabig.curtaincall.event.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.kanabig.curtaincall.common.web.error.BusinessException;
import io.github.kanabig.curtaincall.event.dto.SeatSpec;
import io.github.kanabig.curtaincall.event.entity.SaleSession;
import io.github.kanabig.curtaincall.event.entity.SaleSession.Price;
import io.github.kanabig.curtaincall.event.repository.HallSeatRepository;
import io.github.kanabig.curtaincall.event.repository.HallSeatRepository.SeatRow;
import io.github.kanabig.curtaincall.event.repository.SessionRepository;
import io.github.kanabig.curtaincall.event.service.SeatMapService.Body;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** EVT-9. 캐시 미스가 겹쳐도 회차당 DB 조회는 한 번이고, 실패와 evict 경쟁이 캐시를 오염시키지 않는다. */
class SeatMapServiceTests {

    static final int WAITERS = 50;

    SessionRepository sessions = mock(SessionRepository.class);
    HallSeatRepository seats = mock(HallSeatRepository.class);
    SeatMapService service;
    ExecutorService pool;

    @BeforeEach
    void setUp() {
        service = new SeatMapService(sessions, seats, JsonMapper.builder().build(), Clock.systemUTC());
        pool = Executors.newFixedThreadPool(WAITERS + 2);
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    static SaleSession session() {
        return new SaleSession(
                1, 1, "H-01", Instant.now(), Instant.now(), List.of(new Price("VIP", 150000)), 50, 500, false);
    }

    static List<SeatRow> rows() {
        return List.of(new SeatRow(1, new SeatSpec(1, "A", "1", 1, 0, 0, 0, false)));
    }

    List<Future<Body>> callConcurrently(int n, long sessionId) throws Exception {
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Body>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                return service.get(sessionId);
            }));
        }
        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        go.countDown();
        return futures;
    }

    @Test
    @DisplayName("같은 회차의 캐시 미스가 동시에 N건 와도 DB 조회는 한 번이고 모두 같은 본문을 받는다")
    void concurrentMissesLoadOnce() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger loads = new AtomicInteger();
        when(sessions.find(anyLong())).thenAnswer(inv -> {
            loads.incrementAndGet();
            release.await(5, TimeUnit.SECONDS);
            return Optional.of(session());
        });
        when(seats.findSeatRows("H-01")).thenReturn(rows());

        List<Future<Body>> futures = callConcurrently(WAITERS, 1);
        Thread.sleep(200);
        release.countDown();

        Body first = futures.get(0).get(5, TimeUnit.SECONDS);
        for (Future<Body> f : futures) {
            assertThat(f.get(5, TimeUnit.SECONDS)).isSameAs(first);
        }
        assertThat(loads).hasValue(1);
        service.get(1);
        verify(sessions, times(1)).find(1);
    }

    @Test
    @DisplayName("로딩이 실패하면 대기 중인 요청도 같은 예외를 받고, 실패는 캐시되지 않아 다음 요청이 다시 읽는다")
    void failureIsPropagatedAndNotCached() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        when(sessions.find(anyLong())).thenAnswer(inv -> {
            release.await(5, TimeUnit.SECONDS);
            return Optional.empty();
        });

        List<Future<Body>> futures = callConcurrently(10, 1);
        Thread.sleep(200);
        release.countDown();

        for (Future<Body> f : futures) {
            assertThatThrownBy(() -> f.get(5, TimeUnit.SECONDS)).hasRootCauseInstanceOf(BusinessException.class);
        }
        when(sessions.find(anyLong())).thenReturn(Optional.of(session()));
        when(seats.findSeatRows("H-01")).thenReturn(rows());
        assertThat(service.get(1).json()).isNotEmpty();
    }

    @Test
    @DisplayName("로딩 중에 evict되면 그 결과는 캐시에 남지 않고, 이후 요청이 새로 읽는다")
    void evictDuringLoadDropsResult() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger loads = new AtomicInteger();
        when(sessions.find(anyLong())).thenAnswer(inv -> {
            if (loads.incrementAndGet() == 1) {
                started.countDown();
                release.await(5, TimeUnit.SECONDS);
            }
            return Optional.of(session());
        });
        when(seats.findSeatRows("H-01")).thenReturn(rows());

        Future<Body> stale = pool.submit(() -> service.get(1));
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        service.evictAfterCommit(1);
        Body fresh = service.get(1);
        release.countDown();
        stale.get(5, TimeUnit.SECONDS);

        assertThat(loads).hasValue(2);
        assertThat(service.get(1)).isSameAs(fresh);
        assertThat(loads).hasValue(2);
    }

    @Test
    @DisplayName("이미 캐시된 회차를 다시 읽을 때는 가득 차 있어도 다른 항목을 내보내지 않는다")
    void noEvictionForExistingKey() {
        when(sessions.find(anyLong())).thenReturn(Optional.of(session()));
        when(seats.findSeatRows("H-01")).thenReturn(rows());
        for (long id = 1; id <= SeatMapService.MAX_ENTRIES; id++) {
            service.get(id);
        }
        verify(sessions, times(SeatMapService.MAX_ENTRIES)).find(anyLong());
        for (long id = 1; id <= SeatMapService.MAX_ENTRIES; id++) {
            service.get(id);
        }
        verify(sessions, times(SeatMapService.MAX_ENTRIES)).find(anyLong());
    }
}
