package io.github.kanabig.curtaincall.gateway.config;

import java.nio.charset.StandardCharsets;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 토큰 검증 키. access token 키(user가 서명)와 입장 토큰 키(queue가 서명)는 서로 다른 값이어야 한다. */
@ConfigurationProperties("curtaincall.jwt")
public record JwtProperties(String accessSecret, String entrySecret) {

    /** HS256 키는 해시 길이(256비트)보다 짧으면 안 된다(RFC 7518 3.2). */
    static final int MIN_SECRET_BYTES = 32;

    public JwtProperties {
        require(accessSecret, "access-secret", "CURTAINCALL_JWT_ACCESS_SECRET");
        require(entrySecret, "entry-secret", "CURTAINCALL_JWT_ENTRY_SECRET");
        if (accessSecret.equals(entrySecret)) {
            throw new IllegalStateException("access-secret과 entry-secret은 서로 다른 값이어야 한다");
        }
    }

    private static void require(String secret, String name, String env) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("curtaincall.jwt.%s은 32바이트 이상이어야 한다(%s)".formatted(name, env));
        }
    }
}
