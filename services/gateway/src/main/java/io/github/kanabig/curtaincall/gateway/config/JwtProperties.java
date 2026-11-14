package io.github.kanabig.curtaincall.gateway.config;

import java.nio.charset.StandardCharsets;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** access token 검증 키. user가 서명할 때 쓰는 값과 같아야 하고, 입장 토큰 키와는 별개다. */
@ConfigurationProperties("curtaincall.jwt")
public record JwtProperties(String accessSecret) {

    /** HS256 키는 해시 길이(256비트)보다 짧으면 안 된다(RFC 7518 3.2). */
    static final int MIN_SECRET_BYTES = 32;

    public JwtProperties {
        if (accessSecret == null || accessSecret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "curtaincall.jwt.access-secret은 32바이트 이상이어야 한다(CURTAINCALL_JWT_ACCESS_SECRET)");
        }
    }
}
