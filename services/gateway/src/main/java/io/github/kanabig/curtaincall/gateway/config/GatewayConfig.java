package io.github.kanabig.curtaincall.gateway.config;

import io.github.kanabig.curtaincall.gateway.filter.RequestIdFilter;
import io.micrometer.context.ContextRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.MDC;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import reactor.core.publisher.Hooks;

@Configuration
@EnableConfigurationProperties(JwtProperties.class)
public class GatewayConfig {

    static {
        // Reactor Context의 requestId가 operator를 지나는 스레드의 MDC로 옮겨져 로그 패턴 %X{requestId}에 찍힌다
        ContextRegistry.getInstance()
                .registerThreadLocalAccessor(
                        RequestIdFilter.CONTEXT_KEY,
                        () -> MDC.get(RequestIdFilter.CONTEXT_KEY),
                        value -> MDC.put(RequestIdFilter.CONTEXT_KEY, value),
                        () -> MDC.remove(RequestIdFilter.CONTEXT_KEY));
        Hooks.enableAutomaticContextPropagation();
    }

    @Bean
    ReactiveJwtDecoder accessTokenDecoder(JwtProperties properties) {
        SecretKey key = new SecretKeySpec(properties.accessSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withSecretKey(key)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        // 서비스가 같은 호스트의 컨테이너라 시계 오차를 허용하지 않는다(기본값은 60초)
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                List.of(new JwtTimestampValidator(Duration.ZERO), this::hasUserIdAndRole)));
        return decoder;
    }

    /** sub와 role이 없는 토큰은 서명이 맞아도 받지 않는다. 서비스는 이 두 헤더를 그대로 믿는다. */
    private OAuth2TokenValidatorResult hasUserIdAndRole(Jwt jwt) {
        String role = jwt.getClaimAsString("role");
        if (jwt.getSubject() != null && !jwt.getSubject().isBlank() && ("USER".equals(role) || "ADMIN".equals(role))) {
            return OAuth2TokenValidatorResult.success();
        }
        return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "sub 또는 role 클레임이 올바르지 않다", null));
    }
}
