package io.github.kanabig.curtaincall.reservation.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.kanabig.curtaincall.common.web.metrics.MetricNames;
import io.github.kanabig.curtaincall.reservation.service.OrderSagaService;
import io.github.kanabig.curtaincall.reservation.service.OrderService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.SQLException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DeadlockLoserDataAccessException;

class OrderSchedulerTests {

    private final OrderSagaService saga = mock(OrderSagaService.class);
    private final OrderService orders = mock(OrderService.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private OrderScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new OrderScheduler(saga, orders, registry);
    }

    private double failures(String task, String cause) {
        return registry.counter(MetricNames.ORDER_SCHEDULER_FAILURE, "task", task, "cause", cause)
                .count();
    }

    @Test
    void expireDeadlockIsCountedNotThrown() {
        when(orders.expireOverdue())
                .thenThrow(
                        new CannotAcquireLockException("Deadlock found", new SQLException("deadlock", "40001", 1213)));

        scheduler.run();

        assertThat(failures("expire", "deadlock")).isEqualTo(1);
        verify(saga).resendDue();
    }

    @Test
    void resendLockTimeoutDoesNotStopExpire() {
        when(saga.resendDue())
                .thenThrow(new CannotAcquireLockException("timeout", new SQLException("wait", "HY000", 1205)));

        scheduler.run();

        assertThat(failures("resend", "lock_timeout")).isEqualTo(1);
        verify(orders).expireOverdue();
    }

    @Test
    void deadlockExceptionTypeCountsAsDeadlock() {
        when(orders.expireOverdue()).thenThrow(new DeadlockLoserDataAccessException("x", null));

        scheduler.run();

        assertThat(failures("expire", "deadlock")).isEqualTo(1);
    }

    @Test
    void nonLockExceptionPropagates() {
        when(saga.resendDue()).thenThrow(new DataAccessResourceFailureException("db down"));

        assertThatThrownBy(scheduler::run).isInstanceOf(DataAccessResourceFailureException.class);
    }
}
