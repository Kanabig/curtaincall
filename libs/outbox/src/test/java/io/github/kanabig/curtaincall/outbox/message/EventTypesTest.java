package io.github.kanabig.curtaincall.outbox.message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class EventTypesTest {

    @Test
    @DisplayName("첫 버전은 접미사가 없고 두 번째부터 .vN이 붙는다")
    void versionedName() {
        assertThat(EventTypes.of("PaymentApproved", 1)).isEqualTo("PaymentApproved");
        assertThat(EventTypes.of("PaymentApproved", 2)).isEqualTo("PaymentApproved.v2");
    }

    @Test
    @DisplayName("이름과 버전을 다시 꺼낸다")
    void parse() {
        assertThat(EventTypes.nameOf("PaymentApproved.v2")).isEqualTo("PaymentApproved");
        assertThat(EventTypes.versionOf("PaymentApproved.v2")).isEqualTo(2);
        assertThat(EventTypes.nameOf("PaymentApproved")).isEqualTo("PaymentApproved");
        assertThat(EventTypes.versionOf("PaymentApproved")).isEqualTo(1);
        assertThat(EventTypes.versionOf("Something.vNext")).isEqualTo(1);
    }

    @Test
    @DisplayName("버전은 1 이상이어야 한다")
    void invalidVersion() {
        assertThatThrownBy(() -> EventTypes.of("PaymentApproved", 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
