package io.github.kanabig.curtaincall.user.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("curtaincall.user")
/**
 * {@code refreshReuseGrace}: 회전된 지 이 시간 안에 같은 토큰이 다시 오면 탈취가 아니라 같은 브라우저의 탭 두 개가 동시에 회전한 것으로 보고 계열은 두고 그 요청만 거절한다.
 */
public record UserProperties(
        Duration refreshTtl, @DefaultValue("10s") Duration refreshReuseGrace, Admin admin) {

    /** 둘 다 비어 있으면 시드 계정을 만들지 않는다. */
    public record Admin(String email, String password) {

        public boolean configured() {
            return email != null && !email.isBlank() && password != null && !password.isBlank();
        }

        @Override
        public String toString() {
            return "Admin[email=" + email + ", password=****]";
        }
    }
}
