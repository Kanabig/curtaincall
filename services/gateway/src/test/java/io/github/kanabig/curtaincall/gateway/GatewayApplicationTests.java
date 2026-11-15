package io.github.kanabig.curtaincall.gateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "curtaincall.jwt.access-secret=test-access-secret-0123456789abcdef-test",
            "curtaincall.jwt.entry-secret=test-entry-secret-0123456789abcdef-test",
            "management.server.port=0"
        })
class GatewayApplicationTests {

    @Autowired
    WebTestClient webTestClient;

    @LocalManagementPort
    int managementPort;

    @Test
    @DisplayName("관리 포트의 헬스체크가 UP을 응답한다")
    void healthIsUp() {
        WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + managementPort)
                .build()
                .get()
                .uri("/actuator/health")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.status")
                .isEqualTo("UP");
    }

    @Test
    @DisplayName("actuator는 외부 포트로 노출되지 않는다")
    void actuatorIsNotOnPublicPort() {
        webTestClient.get().uri("/actuator/health").exchange().expectStatus().isNotFound();
    }
}
