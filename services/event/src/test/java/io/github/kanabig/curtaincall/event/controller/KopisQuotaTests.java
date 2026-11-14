package io.github.kanabig.curtaincall.event.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.kanabig.curtaincall.event.EventIntegrationTest;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** ADM-4: 실제 KOPIS 호출(live 모드)은 남은 호출 수가 한 번의 상한보다 적으면 시작하지 않는다. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "curtaincall.kopis.mode=live",
            "curtaincall.kopis.api-key=test-key",
            "curtaincall.kopis.base-url=http://localhost:1",
            "curtaincall.kopis.scheduler.enabled=false",
            "curtaincall.kopis.bootstrap=false",
            "curtaincall.kopis.daily-limit=100",
            "curtaincall.kopis.sync-max-calls=60",
            "curtaincall.outbox.relay.auto-start=false"
        })
class KopisQuotaTests extends EventIntegrationTest {

    @Autowired
    TestRestTemplate rest;

    @Test
    @DisplayName("남은 호출이 상한(60)보다 적으면 409 KOPIS_QUOTA_LOW이고 동기화가 시작되지 않는다")
    void quotaLow() {
        jdbc.update(
                "INSERT INTO kopis_call_log (call_date, count) VALUES (?, 41)", LocalDate.now(ZoneId.of("Asia/Seoul")));
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-User-Role", "ADMIN");

        ResponseEntity<JsonNode> response =
                rest.exchange("/api/v1/admin/kopis/sync", HttpMethod.POST, new HttpEntity<>(headers), JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().get("code").asText()).isEqualTo("KOPIS_QUOTA_LOW");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM kopis_sync_run", Integer.class))
                .isZero();
    }
}
