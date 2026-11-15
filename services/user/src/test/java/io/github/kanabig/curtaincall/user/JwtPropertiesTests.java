package io.github.kanabig.curtaincall.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.kanabig.curtaincall.user.config.JwtProperties;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JwtPropertiesTests {

    @Test
    @DisplayName("서명 키가 32바이트 미만이거나 없으면 기동에 실패한다")
    void rejectsShortSecret() {
        assertThatThrownBy(() -> new JwtProperties("short", Duration.ofMinutes(30)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new JwtProperties(null, Duration.ofMinutes(30)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("32바이트 키와 양수 TTL은 받아들인다")
    void acceptsValid() {
        assertThat(new JwtProperties("x".repeat(32), Duration.ofMinutes(30)).accessTtl())
                .isEqualTo(Duration.ofMinutes(30));
    }
}
