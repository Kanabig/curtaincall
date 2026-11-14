package io.github.kanabig.curtaincall.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;

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
            "curtaincall.hold.strategy=pessimistic-lock"
        })
class PessimisticLockSeatHoldTests extends AbstractSeatHoldTests {

    @Autowired
    SeatHoldStrategy strategy;

    @Test
    @DisplayName("설정으로 고른 전략이 쓰인다")
    void usesConfiguredStrategy() {
        assertThat(strategy).isInstanceOf(PessimisticLockSeatHoldStrategy.class);
    }
}
