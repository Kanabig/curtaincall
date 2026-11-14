package io.github.kanabig.curtaincall.reservation.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** RSV-5~7 주문 상태 전이. */
class OrderStatusTests {

    @Test
    @DisplayName("허용된 전이는 명세의 상태도와 같다")
    void allowedTransitions() {
        assertThat(OrderStatus.PENDING_PAYMENT.next())
                .containsExactlyInAnyOrder(OrderStatus.PAYMENT_REQUESTED, OrderStatus.EXPIRED);
        assertThat(OrderStatus.PAYMENT_REQUESTED.next())
                .containsExactlyInAnyOrder(OrderStatus.CONFIRMED, OrderStatus.FAILED, OrderStatus.REFUNDING);
        assertThat(OrderStatus.REFUNDING.next()).containsExactly(OrderStatus.REFUNDED);
    }

    @ParameterizedTest
    @EnumSource(
            value = OrderStatus.class,
            names = {"CONFIRMED", "FAILED", "EXPIRED", "REFUNDED"})
    @DisplayName("종결 상태에서는 어디로도 전이할 수 없다")
    void terminalStatesAllowNothing(OrderStatus terminal) {
        assertThat(terminal.isTerminal()).isTrue();
        for (OrderStatus target : OrderStatus.values()) {
            assertThatThrownBy(() -> terminal.requireTransitionTo(target)).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    @DisplayName("결제 요청 전에 확정·환불로 건너뛰거나, 승인 후 상태로 되돌아가는 전이는 거부한다")
    void rejectsSkippingAndRollback() {
        Set<OrderStatus[]> illegal = Set.of(
                new OrderStatus[] {OrderStatus.PENDING_PAYMENT, OrderStatus.CONFIRMED},
                new OrderStatus[] {OrderStatus.PENDING_PAYMENT, OrderStatus.REFUNDING},
                new OrderStatus[] {OrderStatus.PAYMENT_REQUESTED, OrderStatus.EXPIRED},
                new OrderStatus[] {OrderStatus.PAYMENT_REQUESTED, OrderStatus.PENDING_PAYMENT},
                new OrderStatus[] {OrderStatus.REFUNDING, OrderStatus.CONFIRMED},
                new OrderStatus[] {OrderStatus.REFUNDING, OrderStatus.FAILED});
        for (OrderStatus[] pair : illegal) {
            assertThatThrownBy(() -> pair[0].requireTransitionTo(pair[1])).isInstanceOf(IllegalStateException.class);
        }
    }
}
