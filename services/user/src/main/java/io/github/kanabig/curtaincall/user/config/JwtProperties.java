package io.github.kanabig.curtaincall.user.config;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** access token 서명 설정. 키는 gateway가 같은 값으로 검증한다. 입장 토큰 키와는 별개다. */
@ConfigurationProperties("curtaincall.jwt")
public record JwtProperties(String accessSecret, Duration accessTtl) {

    /** HS256 키는 해시 길이(256비트)보다 짧으면 안 된다(RFC 7518 3.2). */
    static final int MIN_SECRET_BYTES = 32;

    public JwtProperties {
        if (accessSecret == null || accessSecret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "curtaincall.jwt.access-secret은 32바이트 이상이어야 한다(CURTAINCALL_JWT_ACCESS_SECRET)");
        }
        if (accessTtl == null || accessTtl.isZero() || accessTtl.isNegative()) {
            throw new IllegalStateException("curtaincall.jwt.access-ttl은 0보다 커야 한다");
        }
    }
}
