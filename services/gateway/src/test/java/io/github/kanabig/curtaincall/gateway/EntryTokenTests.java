package io.github.kanabig.curtaincall.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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

/** [QUE-5] 점유·주문·결제 시작 API의 입장 토큰 검증. 스텁이 받은 헤더로 gateway가 무엇을 넘기는지 확인한다. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "curtaincall.jwt.access-secret=" + EntryTokenTests.ACCESS_SECRET,
            "curtaincall.jwt.entry-secret=" + EntryTokenTests.ENTRY_SECRET,
            "management.server.port=0"
        })
class EntryTokenTests {

    static final String ACCESS_SECRET = "test-access-secret-0123456789abcdef-test";
    static final String ENTRY_SECRET = "test-entry-secret-0123456789abcdef-test";

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

    @BeforeEach
    void clear() {
        RECEIVED.clear();
    }

    @Test
    @DisplayName("유효한 토큰이면 sid를 X-Entry-Session-Id로 넘기고 토큰 헤더는 넘기지 않는다")
    void validToken() {
        post("/api/v1/sessions/5/holds", entry("7", "5", 600, ENTRY_SECRET), "7", "99")
                .expectStatus()
                .isOk();
        Map<String, String> headers = RECEIVED.get("/api/v1/sessions/5/holds");
        assertThat(headers).containsEntry("x-entry-session-id", "5").doesNotContainKey("x-entry-token");
    }

    @Test
    @DisplayName("주문 생성과 결제 시작에도 같은 검증이 걸린다")
    void ordersAndPayment() {
        post("/api/v1/orders", entry("7", "5", 600, ENTRY_SECRET), "7", null)
                .expectStatus()
                .isOk();
        post("/api/v1/orders/ord_abc/payment", entry("7", "5", 600, ENTRY_SECRET), "7", null)
                .expectStatus()
                .isOk();
        assertThat(RECEIVED.get("/api/v1/orders")).containsEntry("x-entry-session-id", "5");
        assertThat(RECEIVED.get("/api/v1/orders/ord_abc/payment")).containsEntry("x-entry-session-id", "5");

        post("/api/v1/orders", null, "7", null).expectStatus().isForbidden();
        post("/api/v1/orders/ord_abc/payment", null, "7", null).expectStatus().isForbidden();
    }

    @Test
    @DisplayName("토큰이 없으면 403 ENTRY_TOKEN_INVALID이고 서비스까지 가지 않는다")
    void missing() {
        post("/api/v1/sessions/5/holds", null, "7", null)
                .expectStatus()
                .isForbidden()
                .expectHeader()
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("ENTRY_TOKEN_INVALID");
        assertThat(RECEIVED).isEmpty();
    }

    @Test
    @DisplayName("만료·변조·깨진 토큰은 403")
    void rejected() {
        String expired = entry("7", "5", -10, ENTRY_SECRET);
        String forged = entry("7", "5", 600, "another-secret-0123456789abcdef-xxxx");
        // access token 키로 서명한 토큰은 입장 토큰으로 쓸 수 없다
        String wrongKind = entry("7", "5", 600, ACCESS_SECRET);
        for (String token : new String[] {expired, forged, wrongKind, "not.a.jwt"}) {
            post("/api/v1/sessions/5/holds", token, "7", null).expectStatus().isForbidden();
        }
        assertThat(RECEIVED).isEmpty();
    }

    @Test
    @DisplayName("sub가 access token의 사용자와 다르면 403")
    void otherUser() {
        post("/api/v1/sessions/5/holds", entry("8", "5", 600, ENTRY_SECRET), "7", null)
                .expectStatus()
                .isForbidden();
        assertThat(RECEIVED).isEmpty();
    }

    @Test
    @DisplayName("sid가 없거나 숫자가 아닌 토큰은 서명이 맞아도 403")
    void badSid() {
        post("/api/v1/sessions/5/holds", entry("7", null, 600, ENTRY_SECRET), "7", null)
                .expectStatus()
                .isForbidden();
        post("/api/v1/sessions/5/holds", entry("7", "5;x", 600, ENTRY_SECRET), "7", null)
                .expectStatus()
                .isForbidden();
        assertThat(RECEIVED).isEmpty();
    }

    @Test
    @DisplayName("입장 토큰이 필요 없는 경로는 토큰 없이 통과하고, 실어 보내도 넘기지 않는다")
    void unprotectedPaths() {
        client.get()
                .uri("/api/v1/orders/ord_abc")
                .headers(h -> h.setBearerAuth(access("7")))
                .header("X-Entry-Token", entry("7", "5", 600, ENTRY_SECRET))
                .exchange()
                .expectStatus()
                .isOk();
        assertThat(RECEIVED.get("/api/v1/orders/ord_abc")).doesNotContainKey("x-entry-token");
        client.method(HttpMethod.POST)
                .uri("/api/v1/queue/sessions/5/entries")
                .headers(h -> h.setBearerAuth(access("7")))
                .exchange()
                .expectStatus()
                .isOk();
    }

    private WebTestClient.ResponseSpec post(String uri, String entryToken, String userId, String spoofedSid) {
        return client.post()
                .uri(uri)
                .headers(h -> {
                    h.setBearerAuth(access(userId));
                    if (entryToken != null) {
                        h.set("X-Entry-Token", entryToken);
                    }
                    if (spoofedSid != null) {
                        h.set("X-Entry-Session-Id", spoofedSid);
                    }
                })
                .exchange();
    }

    private static String access(String sub) {
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(sub)
                .claim("role", "USER")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(600))
                .build();
        return encode(claims, ACCESS_SECRET);
    }

    private static String entry(String sub, String sid, long ttlSeconds, String secret) {
        JwtClaimsSet.Builder claims =
                JwtClaimsSet.builder().subject(sub).expiresAt(Instant.now().plusSeconds(ttlSeconds));
        if (ttlSeconds < 0) {
            claims.issuedAt(Instant.now().plusSeconds(ttlSeconds - 60));
        }
        if (sid != null) {
            claims.claim("sid", sid);
        }
        return encode(claims.build(), secret);
    }

    private static String encode(JwtClaimsSet claims, String secret) {
        NimbusJwtEncoder encoder = new NimbusJwtEncoder(
                new ImmutableSecret<>(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256")));
        return encoder.encode(JwtEncoderParameters.from(
                        JwsHeader.with(MacAlgorithm.HS256).build(), claims))
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
