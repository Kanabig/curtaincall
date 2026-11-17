package io.github.kanabig.curtaincall.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.redis.testcontainers.RedisContainer;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * [GW-2] Redis가 응답하지 않는 동안(컨테이너 일시 정지)의 서킷 동작. 첫 실패 뒤에는 Redis를 부르지 않아 요청이 타임아웃을 기다리지 않고, 복구되면 한도가 다시
 * 적용된다.
 */
@AutoConfigureWebTestClient
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "curtaincall.jwt.access-secret=test-access-secret-0123456789abcdef-test",
            "curtaincall.jwt.entry-secret=test-entry-secret-0123456789abcdef-test",
            "management.server.port=0",
            "curtaincall.rate-limit.timeout=100ms",
            "curtaincall.rate-limit.circuit-open=1s",
            "curtaincall.rate-limit.ip.rate-per-second=0.01",
            "curtaincall.rate-limit.ip.burst=2"
        })
@ExtendWith(OutputCaptureExtension.class)
class RateLimitRecoveryTests {

    static final StubBackend STUB = new StubBackend();
    static final RedisContainer REDIS = new RedisContainer("redis:8.0.6");

    static {
        REDIS.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
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
    MeterRegistry meters;

    @Test
    @DisplayName("Redis가 멈추면 서킷이 열려 요청이 타임아웃을 기다리지 않고, 복구되면 한도가 다시 걸린다")
    void circuitOpensAndRecovers(CapturedOutput output) {
        var docker = REDIS.getDockerClient();
        docker.pauseContainerCmd(REDIS.getContainerId()).exec();
        try {
            // 첫 요청이 타임아웃으로 서킷을 연다
            client.get().uri("/api/v1/performances").exchange().expectStatus().isOk();
            double failopen = failopenCount();

            long start = System.nanoTime();
            for (int i = 0; i < 5; i++) {
                client.get()
                        .uri("/api/v1/performances")
                        .exchange()
                        .expectStatus()
                        .isOk();
            }
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(450));
            // 건너뛴 요청도 센다
            assertThat(failopenCount()).isEqualTo(failopen + 5);
        } finally {
            docker.unpauseContainerCmd(REDIS.getContainerId()).exec();
        }
        assertThat(output.getOut()).contains("속도 제한 Redis 호출 실패");

        // circuit-open(1초)이 지나면 요청 하나가 Redis를 다시 불러 닫는다. unpause 직후에는 재연결이 늦어 그 요청이 실패하고
        // 서킷이 다시 열릴 수 있으므로, 고정 대기 대신 한도가 걸려 429가 나올 때까지 기다린다.
        // 서킷이 열린 동안 건너뛴 요청은 토큰을 쓰지 않아 IP 한도(burst 2)는 복구 뒤 요청부터 센다
        await().atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> client.get()
                        .uri("/api/v1/performances")
                        .exchange()
                        .expectStatus()
                        .isEqualTo(429));
        assertThat(output.getOut()).contains("복구되어 한도를 다시 적용한다");
    }

    private double failopenCount() {
        var counter =
                meters.find("gateway.ratelimit.failopen").tag("scope", "ip").counter();
        return counter == null ? 0 : counter.count();
    }
}
