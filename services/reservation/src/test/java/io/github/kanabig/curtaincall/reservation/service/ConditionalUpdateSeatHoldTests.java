package io.github.kanabig.curtaincall.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;

import io.github.kanabig.curtaincall.common.web.error.BusinessException;
import io.github.kanabig.curtaincall.reservation.repository.SeatHoldRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@SpringBootTest(
        properties = {
            "spring.kafka.listener.auto-startup=false",
            "curtaincall.inventory.scheduler.enabled=false",
            "curtaincall.order.scheduler.enabled=false",
            "curtaincall.outbox.relay.auto-start=false",
            "curtaincall.hold.strategy=conditional-update"
        })
class ConditionalUpdateSeatHoldTests extends AbstractSeatHoldTests {

    @Autowired
    SeatHoldStrategy strategy;

    @MockitoSpyBean
    SeatHoldRepository repository;

    @Test
    @DisplayName("설정으로 고른 전략이 쓰인다")
    void usesConfiguredStrategy() {
        assertThat(strategy).isInstanceOf(ConditionalUpdateSeatHoldStrategy.class);
    }

    @Test
    @DisplayName("이미 점유된 좌석 요청은 한도 행을 만들지 않고 409로 끝난다")
    void precheckRejectsWithoutQuotaWrite() {
        seed(SESSION, 10);
        service.hold(SESSION, 100L, List.of(1L));

        assertThatThrownBy(() -> service.hold(SESSION, 200L, List.of(1L, 2L)))
                .satisfies(e -> assertThat(isSeatConflict(e)).isTrue());

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_session_quota WHERE user_id = 200", Integer.class))
                .isZero();
    }

    @Test
    @DisplayName("만료된 점유는 사전 확인에서 빈 좌석으로 본다")
    void precheckTreatsExpiredHoldAsFree() {
        seed(SESSION, 10);
        jdbc.update(
                "UPDATE seat_inventory SET status = 'HELD', hold_id = 'old', holder_id = 100,"
                        + " expires_at = NOW(6) - INTERVAL 1 MINUTE WHERE session_id = ? AND seat_id = 1",
                SESSION);

        assertThat(service.hold(SESSION, 200L, List.of(1L)).seatIds()).containsExactly(1L);
    }

    @Test
    @DisplayName("없는 좌석이 섞이면 사전 확인을 거쳐도 400이다")
    void precheckKeepsMissingSeatError() {
        seed(SESSION, 10);
        service.hold(SESSION, 100L, List.of(1L));

        assertThatThrownBy(() -> service.hold(SESSION, 200L, List.of(1L, 99L)))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        e -> assertThat(isSeatConflict(e)).isFalse());
    }

    @Test
    @DisplayName("사전 확인을 통과해도 조건부 UPDATE가 최종 판정한다")
    void conditionalUpdateDecidesAfterPrecheckPasses() {
        seed(SESSION, 10);
        service.hold(SESSION, 100L, List.of(1L));
        // 첫 사전 확인만 "모두 비었다"고 답하게 해 경쟁에 진 상황을 만든다. 이후 호출(conflict의 재조회)은 실제 값
        doReturn(List.of())
                .doCallRealMethod()
                .when(repository)
                .findUnavailable(anyLong(), anyList(), any(Instant.class));

        assertThatThrownBy(() -> service.hold(SESSION, 200L, List.of(1L)))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(isSeatConflict(e)).isTrue();
                    assertThat(e.properties()).containsEntry("seatIds", List.of(1L));
                });

        assertThat(jdbc.queryForObject(
                        "SELECT holder_id FROM seat_inventory WHERE session_id = ? AND seat_id = 1",
                        Long.class,
                        SESSION))
                .isEqualTo(100L);
        assertThat(repository.countOwned(SESSION, 200L, Instant.now())).isZero();
    }

    @Test
    @DisplayName("사전 확인을 통과해도 일부 좌석만 점유된 요청은 전부 실패한다")
    void partiallyTakenSetFailsAfterPrecheckPasses() {
        seed(SESSION, 10);
        service.hold(SESSION, 100L, List.of(1L));
        doReturn(List.of())
                .doCallRealMethod()
                .when(repository)
                .findUnavailable(anyLong(), anyList(), any(Instant.class));

        assertThatThrownBy(() -> service.hold(SESSION, 200L, List.of(1L, 2L)))
                .satisfies(e -> assertThat(isSeatConflict(e)).isTrue());

        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM seat_inventory WHERE session_id = ? AND holder_id = 200",
                        Integer.class,
                        SESSION))
                .isZero();
        assertThat(repository.countOwned(SESSION, 200L, Instant.now())).isZero();
    }

    @Test
    @DisplayName("한도를 넘기는 요청이라도 점유된 좌석이 있으면 SEAT_ALREADY_HELD가 먼저다")
    void seatConflictComesBeforeLimitExceeded() {
        seed(SESSION, 10);
        service.hold(SESSION, 200L, List.of(1L, 2L, 3L, 4L));
        service.hold(SESSION, 100L, List.of(5L));

        assertThatThrownBy(() -> service.hold(SESSION, 200L, List.of(5L)))
                .satisfies(e -> assertThat(isSeatConflict(e)).isTrue());
        // 빈 좌석이면 한도 초과
        assertThatThrownBy(() -> service.hold(SESSION, 200L, List.of(6L)))
                .satisfies(e -> assertThat(isLimit(e)).isTrue());
    }
}
