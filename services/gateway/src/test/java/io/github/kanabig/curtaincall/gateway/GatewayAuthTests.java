package io.github.kanabig.curtaincall.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/** [GW-1, GW-3] 모든 서비스 경로를 한 스텁 서버로 보내고, 스텁이 받은 헤더로 gateway가 무엇을 넘기는지 확인한다. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "curtaincall.jwt.access-secret=" + GatewayAuthTests.SECRET,
            "management.server.port=0",
            "logging.level.io.github.kanabig.curtaincall.gateway.filter=DEBUG"
        })
@ExtendWith(OutputCaptureExtension.class)
class GatewayAuthTests {

    static final String SECRET = "test-access-secret-0123456789abcdef-test";

    /** 스텁이 마지막으로 받은 요청: 경로, 헤더(소문자 이름). 요청이 도착하지 않았으면 null. */
    static final Map<String, Map<String, String>> RECEIVED = new ConcurrentHashMap<>();

    static final HttpServer STUB = startStub();

    @DynamicPropertySource
    static void routes(DynamicPropertyRegistry registry) {
        String url = "http://localhost:" + STUB.getAddress().getPort();
        for (String name : new String[] {"USER", "EVENT", "QUEUE", "RESERVATION", "SEARCH_ASSIST"}) {
            registry.add(name + "_SERVICE_URL", () -> url);
        }
    }

    @AfterAll
    static void stopStub() {
        STUB.stop(0);
    }

    @Autowired
    WebTestClient client;

    @LocalServerPort
    int port;

    @BeforeEach
    void clear() {
        RECEIVED.clear();
    }

    @Test
    @DisplayName("토큰이 없으면 401이고 서비스까지 가지 않는다")
    void noToken() {
        client.get()
                .uri("/api/v1/users/me")
                .exchange()
                .expectStatus()
                .isUnauthorized()
                .expectHeader()
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("UNAUTHORIZED");
        assertThat(RECEIVED).isEmpty();
    }

    @Test
    @DisplayName("만료된 토큰은 401")
    void expiredToken() {
        String token = token(
                "7", "USER", Instant.now().minusSeconds(120), Instant.now().minusSeconds(60), SECRET);
        client.get()
                .uri("/api/v1/users/me")
                .headers(h -> h.setBearerAuth(token))
                .exchange()
                .expectStatus()
                .isUnauthorized();
    }

    @Test
    @DisplayName("다른 키로 서명한 토큰은 401")
    void forgedToken() {
        String token = token(
                "7", "ADMIN", Instant.now(), Instant.now().plusSeconds(600), "another-secret-0123456789abcdef-xxxx");
        client.get()
                .uri("/api/v1/users/me")
                .headers(h -> h.setBearerAuth(token))
                .exchange()
                .expectStatus()
                .isUnauthorized();
    }

    @Test
    @DisplayName("깨진 토큰과 Bearer가 아닌 스킴은 401")
    void malformedToken() {
        client.get()
                .uri("/api/v1/users/me")
                .headers(h -> h.setBearerAuth("not.a.jwt"))
                .exchange()
                .expectStatus()
                .isUnauthorized();
        client.get()
                .uri("/api/v1/users/me")
                .header(HttpHeaders.AUTHORIZATION, "Basic abc")
                .exchange()
                .expectStatus()
                .isUnauthorized();
    }

    @Test
    @DisplayName("exp가 없는 토큰은 서명이 맞아도 401")
    void tokenWithoutExp() {
        String token = token("7", "USER", Instant.now(), null, SECRET);
        client.get()
                .uri("/api/v1/users/me")
                .headers(h -> h.setBearerAuth(token))
                .exchange()
                .expectStatus()
                .isUnauthorized();
    }

    @Test
    @DisplayName("X-User- 접두 헤더는 이름과 대소문자에 상관없이 전달되지 않는다")
    void stripsAnyUserHeader() {
        client.post()
                .uri("/api/v1/auth/login")
                .header("x-user-email", "a@b.c")
                .header("X-USER-Foo", "1")
                .exchange()
                .expectStatus()
                .isOk();
        assertThat(RECEIVED.get("/api/v1/auth/login")).doesNotContainKeys("x-user-email", "x-user-foo");
    }

    @Test
    @DisplayName("role 클레임이 없는 토큰은 서명이 맞아도 401")
    void tokenWithoutRole() {
        String token = token("7", null, Instant.now(), Instant.now().plusSeconds(600), SECRET);
        client.get()
                .uri("/api/v1/users/me")
                .headers(h -> h.setBearerAuth(token))
                .exchange()
                .expectStatus()
                .isUnauthorized();
    }

    @Test
    @DisplayName("유효한 토큰이면 X-User-Id, X-User-Role을 넘기고 외부에서 보낸 값은 버린다")
    void validTokenOverridesSpoofedHeaders() {
        String token = token("7", "USER", Instant.now(), Instant.now().plusSeconds(600), SECRET);
        client.get()
                .uri("/api/v1/users/me")
                .headers(h -> h.setBearerAuth(token))
                .header("X-User-Id", "1")
                .header("X-User-Role", "ADMIN")
                .header("X-Entry-Session-Id", "99")
                .exchange()
                .expectStatus()
                .isOk();
        Map<String, String> headers = RECEIVED.get("/api/v1/users/me");
        assertThat(headers).containsEntry("x-user-id", "7").containsEntry("x-user-role", "USER");
        assertThat(headers).doesNotContainKey("x-entry-session-id");
    }

    @Test
    @DisplayName("공개 경로에서도 외부 X-User-*는 전달되지 않는다")
    void publicPathStripsSpoofedHeaders() {
        client.post()
                .uri("/api/v1/auth/login")
                .header("X-User-Id", "1")
                .header("X-User-Role", "ADMIN")
                .header("X-Entry-Session-Id", "99")
                .exchange()
                .expectStatus()
                .isOk();
        Map<String, String> headers = RECEIVED.get("/api/v1/auth/login");
        assertThat(headers).doesNotContainKeys("x-user-id", "x-user-role", "x-entry-session-id");
    }

    @Test
    @DisplayName("공개 경로는 토큰 없이 통과한다")
    void publicRoutes() {
        client.post().uri("/api/v1/users").exchange().expectStatus().isOk();
        client.post().uri("/api/v1/auth/refresh").exchange().expectStatus().isOk();
        client.post().uri("/api/v1/auth/logout").exchange().expectStatus().isOk();
        client.get()
                .uri("/api/v1/performances?genre=MUSICAL")
                .exchange()
                .expectStatus()
                .isOk();
        client.get().uri("/api/v1/performances/3").exchange().expectStatus().isOk();
        client.get().uri("/api/v1/sessions/5").exchange().expectStatus().isOk();
        client.get()
                .uri("/api/v1/sessions/5/seat-map")
                .exchange()
                .expectStatus()
                .isOk();
        client.post().uri("/api/v1/search/assist").exchange().expectStatus().isOk();
    }

    @Test
    @DisplayName("예매 경로와 같은 이름의 메서드·경로는 공개가 아니다")
    void nonPublicRoutes() {
        client.get()
                .uri("/api/v1/sessions/5/seat-status")
                .exchange()
                .expectStatus()
                .isUnauthorized();
        client.post().uri("/api/v1/sessions/5/holds").exchange().expectStatus().isUnauthorized();
        client.post()
                .uri("/api/v1/queue/sessions/5/entries")
                .exchange()
                .expectStatus()
                .isUnauthorized();
        client.post().uri("/api/v1/performances").exchange().expectStatus().isUnauthorized();
        client.get().uri("/api/v1/reservations").exchange().expectStatus().isUnauthorized();
        assertThat(RECEIVED).isEmpty();
    }

    @Test
    @DisplayName("admin 경로는 USER에게 403, ADMIN은 통과, 토큰이 없으면 401")
    void adminPaths() {
        String user = token("7", "USER", Instant.now(), Instant.now().plusSeconds(600), SECRET);
        String admin = token("1", "ADMIN", Instant.now(), Instant.now().plusSeconds(600), SECRET);

        client.post()
                .uri("/api/v1/admin/performances")
                .exchange()
                .expectStatus()
                .isUnauthorized();
        client.post()
                .uri("/api/v1/admin/performances")
                .headers(h -> h.setBearerAuth(user))
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("FORBIDDEN");
        assertThat(RECEIVED).isEmpty();

        client.post()
                .uri("/api/v1/admin/performances")
                .headers(h -> h.setBearerAuth(admin))
                .exchange()
                .expectStatus()
                .isOk();
        assertThat(RECEIVED.get("/api/v1/admin/performances")).containsEntry("x-user-role", "ADMIN");
    }

    @Test
    @DisplayName("/internal/** 은 토큰이 있어도 404이고 서비스까지 가지 않는다")
    void internalIsClosed() {
        String admin = token("1", "ADMIN", Instant.now(), Instant.now().plusSeconds(600), SECRET);
        client.get().uri("/internal/users/1").exchange().expectStatus().isNotFound();
        client.get()
                .uri("/internal/users/1")
                .headers(h -> h.setBearerAuth(admin))
                .exchange()
                .expectStatus()
                .isNotFound();
        client.get()
                .uri("/internal/sessions/1/seat-map")
                .exchange()
                .expectStatus()
                .isNotFound();
        assertThat(RECEIVED).isEmpty();
    }

    @Test
    @DisplayName("경로 정규화로 /internal에 닿는 우회 형식은 거절한다")
    void traversalIsRejected() {
        String admin = token("1", "ADMIN", Instant.now(), Instant.now().plusSeconds(600), SECRET);
        for (String path : new String[] {
            "/api/v1/performances/../../../internal/users/1",
            "/api/v1/performances/%2e%2e/%2e%2e/internal/users/1",
            "/api/v1/users/..;/..;/internal/users/1",
            "/api/v1/performances//x",
            "/api/v1/performances/%2F..%2F..%2Finternal/users/1",
            "/api/v1/performances/%5c..%5cinternal",
            "/api/v1/performances/x%3b/y",
        }) {
            client.method(HttpMethod.GET)
                    .uri(URI.create("http://localhost:" + port + path))
                    .headers(h -> h.setBearerAuth(admin))
                    .exchange()
                    .expectStatus()
                    .isBadRequest();
        }
        assertThat(RECEIVED).isEmpty();
    }

    @Test
    @DisplayName("이중 인코딩된 경로로 공개 경로 판정을 우회할 수 없다")
    void doubleEncodedPathIsNotPublic() {
        for (String path : new String[] {
            "/api/v1/sessions/1%252Fseat-status",
            "/api/v1/sessions/1%252F..%252Fholds",
            "/api/v1/performances/%252e%252e"
        }) {
            client.method(HttpMethod.GET)
                    .uri(URI.create("http://localhost:" + port + path))
                    .exchange()
                    .expectStatus()
                    .isUnauthorized();
        }
        assertThat(RECEIVED).isEmpty();
    }

    @Test
    @DisplayName("라우트가 없는 경로는 404")
    void unknownPath() {
        String token = token("7", "USER", Instant.now(), Instant.now().plusSeconds(600), SECRET);
        client.get()
                .uri("/api/v1/unknown")
                .headers(h -> h.setBearerAuth(token))
                .exchange()
                .expectStatus()
                .isNotFound();
        client.get()
                .uri("/other")
                .headers(h -> h.setBearerAuth(token))
                .exchange()
                .expectStatus()
                .isNotFound();
    }

    @Test
    @DisplayName("X-Request-Id가 없으면 만들어 서비스와 응답에 싣는다")
    void generatesRequestId() {
        String responseId = client.get()
                .uri("/api/v1/performances")
                .exchange()
                .expectStatus()
                .isOk()
                .returnResult(Void.class)
                .getResponseHeaders()
                .getFirst("X-Request-Id");
        assertThat(responseId).isNotBlank();
        assertThat(RECEIVED.get("/api/v1/performances")).containsEntry("x-request-id", responseId);
    }

    @Test
    @DisplayName("올바른 X-Request-Id는 유지하고, 형식이 이상하면 새로 만든다")
    void keepsOrReplacesRequestId() {
        client.get()
                .uri("/api/v1/performances")
                .header("X-Request-Id", "abc-123")
                .exchange()
                .expectHeader()
                .valueEquals("X-Request-Id", "abc-123");
        assertThat(RECEIVED.get("/api/v1/performances")).containsEntry("x-request-id", "abc-123");

        String replaced = client.get()
                .uri("/api/v1/performances")
                .header("X-Request-Id", "bad id with spaces")
                .exchange()
                .returnResult(Void.class)
                .getResponseHeaders()
                .getFirst("X-Request-Id");
        assertThat(replaced).isNotEqualTo("bad id with spaces").isNotBlank();
    }

    @Test
    @DisplayName("gateway가 거절한 응답에도 X-Request-Id가 실린다")
    void requestIdOnRejection() {
        client.get()
                .uri("/api/v1/reservations")
                .exchange()
                .expectStatus()
                .isUnauthorized()
                .expectHeader()
                .exists("X-Request-Id");
    }

    @Test
    @DisplayName("요청 ID가 gateway 로그의 MDC에 남는다")
    void requestIdInLogs(CapturedOutput output) {
        client.get()
                .uri("/api/v1/users/me")
                .header("X-Request-Id", "trace-77")
                .headers(h -> h.setBearerAuth("not.a.jwt"))
                .exchange()
                .expectStatus()
                .isUnauthorized();
        assertThat(output.getOut()).contains("[gateway,trace-77]").contains("access token 거절");
    }

    private static String token(String sub, String role, Instant issuedAt, Instant expiresAt, String secret) {
        NimbusJwtEncoder encoder = new NimbusJwtEncoder(
                new ImmutableSecret<>(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256")));
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .subject(sub)
                .id(UUID.randomUUID().toString())
                .issuedAt(issuedAt);
        if (expiresAt != null) {
            claims.expiresAt(expiresAt);
        }
        if (role != null) {
            claims.claim("role", role);
        }
        return encoder.encode(JwtEncoderParameters.from(
                        JwsHeader.with(MacAlgorithm.HS256).build(), claims.build()))
                .getTokenValue();
    }

    private static HttpServer startStub() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/", exchange -> {
                Map<String, String> headers = new ConcurrentHashMap<>();
                exchange.getRequestHeaders().forEach((name, values) -> headers.put(name.toLowerCase(), values.get(0)));
                RECEIVED.put(exchange.getRequestURI().getPath(), headers);
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
