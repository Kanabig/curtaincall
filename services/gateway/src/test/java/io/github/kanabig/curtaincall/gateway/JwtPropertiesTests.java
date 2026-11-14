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
        assertThatThrownBy(() -> new JwtProperties("short")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new JwtProperties(null)).isInstanceOf(IllegalStateException.class);
        assertThatCode(() -> new JwtProperties("0123456789abcdef0123456789abcdef"))
                .doesNotThrowAnyException();
    }
}
