package io.github.kanabig.curtaincall.gateway;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.kanabig.curtaincall.gateway.config.JwtProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JwtPropertiesTests {

    @Test
    @DisplayName("키가 32바이트 미만이거나 없으면 기동에 실패한다")
    void rejectsShortSecret() {
        String ok = "0123456789abcdef0123456789abcdef";
        String other = "fedcba9876543210fedcba9876543210";
        assertThatThrownBy(() -> new JwtProperties("short", other)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new JwtProperties(null, other)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new JwtProperties(ok, "short")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new JwtProperties(ok, null)).isInstanceOf(IllegalStateException.class);
        assertThatCode(() -> new JwtProperties(ok, other)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("두 키가 같으면 기동에 실패한다")
    void rejectsSameSecret() {
        String ok = "0123456789abcdef0123456789abcdef";
        assertThatThrownBy(() -> new JwtProperties(ok, ok)).isInstanceOf(IllegalStateException.class);
    }
}
