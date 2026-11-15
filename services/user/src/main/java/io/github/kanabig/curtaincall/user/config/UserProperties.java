package io.github.kanabig.curtaincall.user.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("curtaincall.user")
public record UserProperties(Duration refreshTtl, Admin admin) {

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
