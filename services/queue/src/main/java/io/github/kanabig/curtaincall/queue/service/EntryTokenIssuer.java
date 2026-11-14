package io.github.kanabig.curtaincall.queue.service;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import io.github.kanabig.curtaincall.queue.config.JwtProperties;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Component;

/**
 * [QUE-5] 입장 토큰. HS256, 클레임 sub(userId), sid(sessionId), exp. exp는 활성 목록의 만료 시각과 같아서 같은 입장에 대해 다시 발급해도 같은 만료를 준다. gateway가
 * 서명·만료를 검증하고 sub를 access token의 사용자와 맞춰 본다.
 */
@Component
public class EntryTokenIssuer {

    private final JwtEncoder encoder;

    public EntryTokenIssuer(JwtProperties properties) {
        this.encoder = new NimbusJwtEncoder(new ImmutableSecret<>(
                new SecretKeySpec(properties.entrySecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256")));
    }

    public String issue(long userId, long sessionId, Instant expiresAt) {
        // sid는 문자열로 넣는다. gateway가 숫자 문자열만 받아 X-Entry-Session-Id로 넘긴다
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(String.valueOf(userId))
                .claim("sid", String.valueOf(sessionId))
                .expiresAt(expiresAt)
                .build();
        return encoder.encode(JwtEncoderParameters.from(
                        JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }
}
