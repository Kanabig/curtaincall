package io.github.kanabig.curtaincall.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/** [GW-3] 하위 서비스가 멈추거나 없을 때 설정한 시간 안에 Problem Details 오류가 온다. */
@AutoConfigureWebTestClient
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "curtaincall.jwt.access-secret=test-access-secret-0123456789abcdef-test",
            "curtaincall.jwt.entry-secret=test-entry-secret-0123456789abcdef-test",
            "management.server.port=0",
            "GATEWAY_RESPONSE_TIMEOUT=500ms",
            "GATEWAY_CONNECT_TIMEOUT_MS=300"
        })
class UpstreamTimeoutTests {

    static final HttpServer STUB = startStub();

    private static HttpServer startStub() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/", exchange -> {
                if (exchange.getRequestURI().getPath().endsWith("/42")) {
                    try {
                        Thread.sleep(5_000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void routes(DynamicPropertyRegistry registry) {
        String url = "http://localhost:" + STUB.getAddress().getPort();
        registry.add("EVENT_SERVICE_URL", () -> url);
        // 아무도 듣지 않는 포트
        registry.add("USER_SERVICE_URL", () -> "http://localhost:1");
    }

    @AfterAll
    static void stopStub() {
        STUB.stop(0);
    }

    @Autowired
    WebTestClient client;

    @Autowired
    RouteLocator routeLocator;

    @Test
    @DisplayName("응답이 멈춘 서비스는 제한 시간 안에 504 Problem Details")
    void responseTimeout() {
        long start = System.nanoTime();
        client.mutate()
                .responseTimeout(Duration.ofSeconds(4))
                .build()
                .get()
                .uri("/api/v1/performances/42")
                .exchange()
                .expectStatus()
                .isEqualTo(504)
                .expectHeader()
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("UPSTREAM_TIMEOUT")
                .jsonPath("$.status")
                .isEqualTo(504);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3));
    }

    @Test
    @DisplayName("정상 응답은 그대로 통과한다")
    void normalPath() {
        client.get().uri("/api/v1/performances/1").exchange().expectStatus().isOk();
    }

    @Test
    @DisplayName("연결할 수 없는 서비스는 503 Problem Details")
    void connectionRefused() {
        client.post()
                .uri("/api/v1/auth/login")
                .exchange()
                .expectStatus()
                .isEqualTo(503)
                .expectHeader()
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("UPSTREAM_UNAVAILABLE");
    }

    @Test
    @DisplayName("queue, search-assist 라우트는 metadata로 응답 제한 시간을 따로 가진다")
    void routeMetadata() {
        Map<String, Object> queue = metadata("queue");
        Map<String, Object> search = metadata("search-assist");
        assertThat(queue.get("response-timeout")).hasToString("15000");
        assertThat(search.get("response-timeout")).hasToString("3000");
        assertThat(metadata("event")).doesNotContainKey("response-timeout");
    }

    private Map<String, Object> metadata(String routeId) {
        return routeLocator
                .getRoutes()
                .filter(r -> r.getId().equals(routeId))
                .blockFirst()
                .getMetadata();
    }
}
