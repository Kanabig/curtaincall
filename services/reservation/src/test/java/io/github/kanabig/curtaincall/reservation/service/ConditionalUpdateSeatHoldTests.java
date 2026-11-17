package io.github.kanabig.curtaincall.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.kanabig.curtaincall.common.web.error.BusinessException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

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
}
