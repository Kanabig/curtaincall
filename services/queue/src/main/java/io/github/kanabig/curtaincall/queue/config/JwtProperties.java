package io.github.kanabig.curtaincall.queue.config;

import java.nio.charset.StandardCharsets;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 입장 토큰 서명 키. gateway의 검증 키와 같아야 하고, access token 키와는 별개다. */
@ConfigurationProperties("curtaincall.jwt")
public record JwtProperties(String entrySecret) {

    /** HS256 키는 해시 길이(256비트)보다 짧으면 안 된다(RFC 7518 3.2). */
    static final int MIN_SECRET_BYTES = 32;

    public JwtProperties {
        if (entrySecret == null || entrySecret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "curtaincall.jwt.entry-secret은 32바이트 이상이어야 한다(CURTAINCALL_JWT_ENTRY_SECRET)");
        }
    }
}
