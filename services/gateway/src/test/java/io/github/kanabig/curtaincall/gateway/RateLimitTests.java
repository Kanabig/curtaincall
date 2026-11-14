package io.github.kanabig.curtaincall.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.redis.testcontainers.RedisContainer;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/** [GW-2, QUE-7] 계정·IP별 한도 초과 시 429와 Retry-After. 충전 속도를 느리게 잡아 테스트 중에는 토큰이 다시 차지 않는다. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "curtaincall.jwt.access-secret=" + RateLimitTests.SECRET,
            "management.server.port=0",
            "curtaincall.rate-limit.ip.rate-per-second=0.1",
            "curtaincall.rate-limit.ip.burst=6",
            "curtaincall.rate-limit.user.rate-per-second=0.1",
            "curtaincall.rate-limit.user.burst=3"
        })
class RateLimitTests {

    static final String SECRET = "test-access-secret-0123456789abcdef-test";
    static final StubBackend STUB = new StubBackend();

    /** gateway는 DB·Kafka를 쓰지 않으므로 공용 컨테이너 묶음(MySQL, Kafka 포함) 대신 Redis만 띄운다. compose.yml과 같은 태그. */
    static final RedisContainer REDIS = new RedisContainer("redis:8.0.6");

    static {
        REDIS.start();
    }

    @DynamicPropertySource
    static void routes(DynamicPropertyRegistry registry) {
        STUB.routeAllServicesHere(registry);
        registry.add("spring.data.redis.host", REDIS::getRedisHost);
        registry.add("spring.data.redis.port", REDIS::getRedisPort);
    }

    @AfterAll
    static void stopStub() {
        STUB.server.stop(0);
    }

    @Autowired
    WebTestClient client;

    @Autowired
    ReactiveStringRedisTemplate redis;

    @Autowired
    MeterRegistry meters;

    @BeforeEach
    void reset() {
        redis.keys("gateway:rl:*").flatMap(redis::delete).blockLast();
        STUB.received.set(0);
    }

    @Test
    @DisplayName("계정 한도를 넘으면 429와 Retry-After를 주고 서비스까지 가지 않는다")
    void userLimit() {
        String token = token("7");
        for (int i = 0; i < 3; i++) {
            client.get()
                    .uri("/api/v1/users/me")
                    .headers(h -> h.setBearerAuth(token))
                    .exchange()
                    .expectStatus()
                    .isOk();
        }
        double before = counter("gateway.ratelimit.rejected", "user");
        client.get()
                .uri("/api/v1/users/me")
                .headers(h -> h.setBearerAuth(token))
                .exchange()
                .expectStatus()
                .isEqualTo(429)
                .expectHeader()
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectHeader()
                .value(
                        HttpHeaders.RETRY_AFTER,
                        v -> assertThat(Long.parseLong(v)).isBetween(1L, 10L))
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("TOO_MANY_REQUESTS");
        assertThat(STUB.received.get()).isEqualTo(3);
        assertThat(counter("gateway.ratelimit.rejected", "user")).isEqualTo(before + 1);
    }

    @Test
    @DisplayName("계정 한도는 계정마다 따로 센다")
    void userLimitIsPerUser() {
        String first = token("7");
        for (int i = 0; i < 4; i++) {
            client.get()
                    .uri("/api/v1/users/me")
                    .headers(h -> h.setBearerAuth(first))
                    .exchange();
        }
        client.get()
                .uri("/api/v1/users/me")
                .headers(h -> h.setBearerAuth(token("8")))
                .exchange()
                .expectStatus()
                .isOk();
    }

    @Test
    @DisplayName("공개 경로는 IP 한도만 받는다")
    void publicPathIpLimit() {
        for (int i = 0; i < 6; i++) {
            client.get().uri("/api/v1/performances").exchange().expectStatus().isOk();
        }
        client.get()
                .uri("/api/v1/performances")
                .exchange()
                .expectStatus()
                .isEqualTo(429)
                .expectHeader()
                .exists(HttpHeaders.RETRY_AFTER);
        assertThat(redis.keys("gateway:rl:user:*").count().block()).isZero();
    }

    @Test
    @DisplayName("IP 한도는 토큰이 없거나 틀린 요청(401)에도 걸린다")
    void ipLimitCoversUnauthorizedRequests() {
        for (int i = 0; i < 6; i++) {
            client.get().uri("/api/v1/users/me").exchange().expectStatus().isUnauthorized();
        }
        client.get().uri("/api/v1/users/me").exchange().expectStatus().isEqualTo(429);
    }

    @Test
    @DisplayName("X-Forwarded-For는 기본 설정에서 IP 판정에 쓰지 않는다")
    void forwardedForIgnoredByDefault() {
        for (int i = 0; i < 6; i++) {
            client.get()
                    .uri("/api/v1/performances")
                    .header("X-Forwarded-For", "10.0.0." + i)
                    .exchange()
                    .expectStatus()
                    .isOk();
        }
        client.get()
                .uri("/api/v1/performances")
                .header("X-Forwarded-For", "10.9.9.9")
                .exchange()
                .expectStatus()
                .isEqualTo(429);
    }

    private double counter(String name, String scope) {
        var counter = meters.find(name).tag("scope", scope).counter();
        return counter == null ? 0 : counter.count();
    }

    private static String token(String sub) {
        NimbusJwtEncoder encoder = new NimbusJwtEncoder(
                new ImmutableSecret<>(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256")));
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(sub)
                .id(UUID.randomUUID().toString())
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(600))
                .claim("role", "USER")
                .build();
        return encoder.encode(JwtEncoderParameters.from(
                        JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }
}
