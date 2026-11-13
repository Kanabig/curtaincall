package io.github.kanabig.curtaincall.reservation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReservationApplicationTests {

    @Autowired
    TestRestTemplate restTemplate;

    @Test
    @DisplayName("애플리케이션이 뜨고 헬스체크가 UP을 응답하며 추적 ID 헤더가 붙는다")
    void healthIsUp() {
        ResponseEntity<String> response = restTemplate.getForEntity("/actuator/health", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
        assertThat(response.getHeaders().getFirst("X-Request-Id")).isNotBlank();
    }
}
