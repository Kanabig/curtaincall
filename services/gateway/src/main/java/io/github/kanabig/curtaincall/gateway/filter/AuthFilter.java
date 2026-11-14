package io.github.kanabig.curtaincall.gateway.filter;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.PathContainer;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import reactor.core.publisher.Mono;

/**
 * [GW-1] 인증 경계. 서비스는 여기서 넣어 준 X-User-Id, X-User-Role만 믿는다.
 *
 * <ol>
 *   <li>외부에서 들어온 X-User-*, X-Entry-Session-Id는 인증 여부와 상관없이 가장 먼저 지운다.
 *   <li>/internal/** 은 라우팅하지 않고 404로 닫는다.
 *   <li>공개 경로가 아니면 access token을 검증하고, /api/v1/admin/** 은 ADMIN만 통과시킨다.
 * </ol>
 *
 * <p>WebFilter가 아니라 GlobalFilter인 이유: WebFilter는 관리 포트(actuator)에도 걸려 헬스체크가 401이 된다.
 */
@Component
public class AuthFilter implements GlobalFilter, Ordered {

    static final String USER_ID = "X-User-Id";
    static final String USER_ROLE = "X-User-Role";
    static final String ENTRY_SESSION_ID = "X-Entry-Session-Id";
    private static final String USER_HEADER_PREFIX = "X-User-";
    private static final String BEARER = "Bearer ";
    private static final Logger log = LoggerFactory.getLogger(AuthFilter.class);

    private static final PathPattern INTERNAL = pattern("/internal/**");
    private static final PathPattern ADMIN = pattern("/api/v1/admin/**");

    /** 로그인 없이 부를 수 있는 API. 가입·로그인·재발급, 공연 조회, 검색 보조. */
    private static final List<PublicRoute> PUBLIC_ROUTES = List.of(
            new PublicRoute(HttpMethod.POST, "/api/v1/users"),
            new PublicRoute(HttpMethod.POST, "/api/v1/auth/login"),
            new PublicRoute(HttpMethod.POST, "/api/v1/auth/refresh"),
            new PublicRoute(HttpMethod.POST, "/api/v1/auth/logout"),
            new PublicRoute(HttpMethod.GET, "/api/v1/performances"),
            new PublicRoute(HttpMethod.GET, "/api/v1/performances/{id:[0-9]+}"),
            new PublicRoute(HttpMethod.GET, "/api/v1/sessions/{id:[0-9]+}"),
            new PublicRoute(HttpMethod.GET, "/api/v1/sessions/{id:[0-9]+}/seat-map"),
            new PublicRoute(HttpMethod.POST, "/api/v1/search/assist"));

    private final ReactiveJwtDecoder decoder;
    private final ProblemWriter problems;

    public AuthFilter(ReactiveJwtDecoder decoder, ProblemWriter problems) {
        this.decoder = decoder;
        this.problems = problems;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerWebExchange stripped = exchange.mutate()
                .request(request -> request.headers(headers -> {
                    headers.keySet()
                            .removeIf(name ->
                                    name.regionMatches(true, 0, USER_HEADER_PREFIX, 0, USER_HEADER_PREFIX.length()));
                    headers.remove(ENTRY_SESSION_ID);
                }))
                .build();

        // 라우트 predicate와 같은 RequestPath(원본 경로를 세그먼트로 나눈 뒤 한 번만 디코드)로 판정한다
        PathContainer container = stripped.getRequest().getPath().pathWithinApplication();
        if (isAmbiguous(container)) {
            return reject(stripped, HttpStatus.BAD_REQUEST, "BAD_REQUEST", "허용하지 않는 경로 형식");
        }
        if (INTERNAL.matches(container)) {
            return reject(stripped, HttpStatus.NOT_FOUND, "NOT_FOUND", "존재하지 않는 경로");
        }
        HttpMethod method = stripped.getRequest().getMethod();
        if (PUBLIC_ROUTES.stream().anyMatch(route -> route.matches(method, container))) {
            return chain.filter(withoutAuthorization(stripped));
        }

        String authorization = stripped.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authorization == null || !authorization.startsWith(BEARER)) {
            return unauthorized(stripped);
        }
        return decoder.decode(authorization.substring(BEARER.length()).trim())
                .flatMap(jwt -> authorize(stripped, chain, container, jwt))
                .onErrorResume(ex -> ex instanceof org.springframework.security.oauth2.jwt.JwtException, ex -> {
                    log.debug("access token 거절: {}", ex.getMessage());
                    return unauthorized(stripped);
                });
    }

    private Mono<Void> authorize(ServerWebExchange exchange, GatewayFilterChain chain, PathContainer path, Jwt jwt) {
        String role = jwt.getClaimAsString("role");
        if (ADMIN.matches(path) && !"ADMIN".equals(role)) {
            return reject(exchange, HttpStatus.FORBIDDEN, "FORBIDDEN", "관리자만 사용할 수 있다");
        }
        ServerWebExchange authenticated = exchange.mutate()
                .request(request -> request.headers(headers -> {
                    headers.set(USER_ID, jwt.getSubject());
                    headers.set(USER_ROLE, role);
                    // 검증은 여기서 끝났다. 서비스는 X-User-*만 쓰므로 토큰이 하위 로그에 남지 않게 지운다
                    headers.remove(HttpHeaders.AUTHORIZATION);
                }))
                .build();
        return chain.filter(authenticated);
    }

    /**
     * 서비스(Tomcat)가 정규화해서 라우팅 규칙과 다른 경로로 읽을 수 있는 형식은 막는다. /api/v1/../internal/... 이 /internal/... 이 되는 경로가 대표적이다.
     */
    private static boolean isAmbiguous(PathContainer path) {
        boolean previousSeparator = false;
        for (PathContainer.Element element : path.elements()) {
            if (element instanceof PathContainer.PathSegment segment) {
                String raw = segment.value();
                String decoded = segment.valueToMatch();
                if (raw.indexOf(';') >= 0
                        || decoded.equals(".")
                        || decoded.equals("..")
                        || decoded.indexOf('/') >= 0
                        || decoded.indexOf('\\') >= 0
                        || decoded.indexOf(';') >= 0) {
                    return true;
                }
                previousSeparator = false;
            } else {
                if (previousSeparator) {
                    return true;
                }
                previousSeparator = true;
            }
        }
        return false;
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange) {
        exchange.getResponse().getHeaders().set(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        return reject(exchange, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "유효한 access token이 필요하다");
    }

    private Mono<Void> reject(ServerWebExchange exchange, HttpStatus status, String code, String detail) {
        return problems.write(exchange, status, code, detail);
    }

    /** 공개 경로는 검증하지 않지만, 토큰을 실어 보낸 요청이어도 하위 서비스로는 넘기지 않는다. */
    private static ServerWebExchange withoutAuthorization(ServerWebExchange exchange) {
        return exchange.mutate()
                .request(request -> request.headers(headers -> headers.remove(HttpHeaders.AUTHORIZATION)))
                .build();
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }

    private static PathPattern pattern(String value) {
        return PathPatternParser.defaultInstance.parse(value);
    }

    private record PublicRoute(HttpMethod method, PathPattern pattern) {
        PublicRoute(HttpMethod method, String pattern) {
            this(method, AuthFilter.pattern(pattern));
        }

        boolean matches(HttpMethod requestMethod, PathContainer path) {
            return method.equals(requestMethod) && pattern.matches(path);
        }
    }
}
