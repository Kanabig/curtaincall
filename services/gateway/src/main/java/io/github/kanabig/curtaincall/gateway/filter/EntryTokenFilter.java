package io.github.kanabig.curtaincall.gateway.filter;

import io.github.kanabig.curtaincall.gateway.config.JwtProperties;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.PathContainer;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import reactor.core.publisher.Mono;

/**
 * [QUE-5] 점유, 주문, 결제 시작 API의 입장 토큰 검증. 서명·만료를 확인하고 토큰의 sub가 access token의 사용자(X-User-Id)와 같은지 본 뒤 sid를
 * X-Entry-Session-Id로 넘긴다. 요청한 회차와 sid를 맞춰 보는 것은 reservation 몫이다(주문·결제 요청에는 회차가 경로에 없다). 검증은 토큰만 보고 하므로 Redis가 내려가도
 * 이미 받은 토큰은 계속 통한다(NFR-11).
 *
 * <p>AuthFilter가 X-User-Id를 넣고 사용자 속도 제한이 지난 다음에 돈다. X-Entry-Token은 어느 경로에서든 하위 서비스로 넘기지 않는다.
 */
@Component
public class EntryTokenFilter implements GlobalFilter, Ordered {

    static final String ENTRY_TOKEN = "X-Entry-Token";
    private static final Logger log = LoggerFactory.getLogger(EntryTokenFilter.class);

    private static final List<ProtectedRoute> PROTECTED = List.of(
            new ProtectedRoute(HttpMethod.POST, "/api/v1/sessions/{id:[0-9]+}/holds"),
            new ProtectedRoute(HttpMethod.POST, "/api/v1/orders"),
            new ProtectedRoute(HttpMethod.POST, "/api/v1/orders/{id}/payment"));

    private final ReactiveJwtDecoder decoder;
    private final ProblemWriter problems;

    public EntryTokenFilter(JwtProperties properties, ProblemWriter problems) {
        SecretKey key = new SecretKeySpec(properties.entrySecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        NimbusReactiveJwtDecoder nimbus = NimbusReactiveJwtDecoder.withSecretKey(key)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        nimbus.setJwtValidator(new DelegatingOAuth2TokenValidator<>(List.of(
                new JwtClaimValidator<Instant>(JwtClaimNames.EXP, Objects::nonNull),
                new JwtTimestampValidator(Duration.ZERO),
                EntryTokenFilter::hasSubAndSid)));
        this.decoder = nimbus;
        this.problems = problems;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String token = exchange.getRequest().getHeaders().getFirst(ENTRY_TOKEN);
        ServerWebExchange stripped = exchange.mutate()
                .request(request -> request.headers(headers -> headers.remove(ENTRY_TOKEN)))
                .build();
        PathContainer path = stripped.getRequest().getPath().pathWithinApplication();
        HttpMethod method = stripped.getRequest().getMethod();
        if (PROTECTED.stream().noneMatch(route -> route.matches(method, path))) {
            return chain.filter(stripped);
        }
        if (token == null || token.isBlank()) {
            return invalid(stripped, "입장 토큰이 필요하다");
        }
        String userId = stripped.getRequest().getHeaders().getFirst(AuthFilter.USER_ID);
        return decoder.decode(token.trim())
                .flatMap(jwt -> pass(stripped, chain, jwt, userId))
                .onErrorResume(JwtException.class, ex -> {
                    log.debug("입장 토큰 거절: {}", ex.getMessage());
                    return invalid(stripped, "유효하지 않거나 만료된 입장 토큰");
                });
    }

    private Mono<Void> pass(ServerWebExchange exchange, GatewayFilterChain chain, Jwt jwt, String userId) {
        if (userId == null || !userId.equals(jwt.getSubject())) {
            return invalid(exchange, "다른 사용자의 입장 토큰");
        }
        ServerWebExchange admitted = exchange.mutate()
                .request(request -> request.headers(headers ->
                        headers.set(AuthFilter.ENTRY_SESSION_ID, String.valueOf(jwt.getClaimAsString("sid")))))
                .build();
        return chain.filter(admitted);
    }

    private Mono<Void> invalid(ServerWebExchange exchange, String detail) {
        return problems.write(exchange, HttpStatus.FORBIDDEN, "ENTRY_TOKEN_INVALID", detail);
    }

    /** sub와 숫자 sid가 없는 토큰은 서명이 맞아도 받지 않는다. reservation은 sid를 그대로 믿는다. */
    private static OAuth2TokenValidatorResult hasSubAndSid(Jwt jwt) {
        Object sid = jwt.getClaims().get("sid");
        boolean numericSid = sid instanceof String s && s.matches("[0-9]{1,18}");
        if (jwt.getSubject() != null && jwt.getSubject().matches("[0-9]{1,18}") && numericSid) {
            return OAuth2TokenValidatorResult.success();
        }
        return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "sub 또는 sid 클레임이 올바르지 않다", null));
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 30;
    }

    private record ProtectedRoute(HttpMethod method, PathPattern pattern) {
        ProtectedRoute(HttpMethod method, String pattern) {
            this(method, PathPatternParser.defaultInstance.parse(pattern));
        }

        boolean matches(HttpMethod requestMethod, PathContainer path) {
            return method.equals(requestMethod) && pattern.matches(path);
        }
    }
}
