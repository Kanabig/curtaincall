package io.github.kanabig.curtaincall.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.redis.testcontainers.RedisContainer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/** [QUE-5] 입장 토큰 요청이 마지막 활동을 남기고, queue가 활성 목록에서 뺀 사용자의 토큰은 서명이 맞아도 거절한다. */
@AutoConfigureWebTestClient
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "curtaincall.jwt.access-secret=" + EntryActivityTests.ACCESS,
            "curtaincall.jwt.entry-secret=" + EntryActivityTests.ENTRY,
            "management.server.port=0",
            "curtaincall.entry-activity.record-interval=30s"
        })
class EntryActivityTests {

    static final String ACCESS = "test-access-secret-0123456789abcdef-test";
    static final String ENTRY = "test-entry-secret-0123456789abcdef-test";
    static final StubBackend STUB = new StubBackend();
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
    StringRedisTemplate redis;

    @BeforeEach
    void clean() {
        redis.delete(java.util.List.of("q:5:active", "q:5:activity"));
        STUB.received.set(0);
    }

    private void activate(String userId, long expiresInMs) {
        redis.opsForZSet().add("q:5:active", userId, System.currentTimeMillis() + expiresInMs);
    }

    @Test
    @DisplayName("활성 목록에 있는 사용자의 요청은 통과하고 마지막 활동이 남는다")
    void recordsActivity() {
        activate("7", 600_000);
        hold("7").expectStatus().isOk();
        Double first = redis.opsForZSet().score("q:5:activity", "7");
        assertThat(first).isNotNull();
    }

    @Test
    @DisplayName("활동 기록은 사용자당 기록 간격 안에서는 다시 쓰지 않는다")
    void throttlesWrites() {
        activate("7", 600_000);
        hold("7").expectStatus().isOk();
        Double first = redis.opsForZSet().score("q:5:activity", "7");
        hold("7").expectStatus().isOk();
        assertThat(redis.opsForZSet().score("q:5:activity", "7")).isEqualTo(first);

        // 기록이 간격보다 오래됐으면 갱신한다
        redis.opsForZSet().add("q:5:activity", "7", first - 31_000);
        hold("7").expectStatus().isOk();
        assertThat(redis.opsForZSet().score("q:5:activity", "7")).isGreaterThan(first - 1);
    }

    @Test
    @DisplayName("활성 목록에서 빠진 사용자의 토큰은 서명이 맞아도 403 ENTRY_TOKEN_INVALID이고 활동도 남기지 않는다")
    void reclaimedTokenRejected() {
        hold("7").expectStatus().isForbidden().expectBody().jsonPath("$.code").isEqualTo("ENTRY_TOKEN_INVALID");
        assertThat(STUB.received.get()).isZero();
        assertThat(redis.opsForZSet().score("q:5:activity", "7")).isNull();
    }

    @Test
    @DisplayName("활성 목록의 만료 시각이 지난 사용자도 거절한다")
    void expiredActiveRejected() {
        activate("7", -1000);
        hold("7").expectStatus().isForbidden();
    }

    private WebTestClient.ResponseSpec hold(String userId) {
        return client.post()
                .uri("/api/v1/sessions/5/holds")
                .headers(h -> {
                    h.setBearerAuth(token(userId, null, ACCESS, 600));
                    h.set("X-Entry-Token", token(userId, "5", ENTRY, 600));
                })
                .exchange();
    }

    private static String token(String sub, String sid, String secret, long ttl) {
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .subject(sub)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(ttl));
        if (sid != null) {
            claims.claim("sid", sid);
        } else {
            claims.claim("role", "USER");
        }
        NimbusJwtEncoder encoder = new NimbusJwtEncoder(
                new ImmutableSecret<>(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256")));
        return encoder.encode(JwtEncoderParameters.from(
                        JwsHeader.with(MacAlgorithm.HS256).build(), claims.build()))
                .getTokenValue();
    }
}
