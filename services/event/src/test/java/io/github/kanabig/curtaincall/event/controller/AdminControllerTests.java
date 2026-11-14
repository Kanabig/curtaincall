package io.github.kanabig.curtaincall.event.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kanabig.curtaincall.event.EventIntegrationTest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** ADM-1~4 관리자 API. 시각은 고정 시계로 다룬다. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "curtaincall.kopis.scheduler.enabled=false",
            "curtaincall.kopis.bootstrap=false",
            "curtaincall.outbox.relay.auto-start=false"
        })
@Import(AdminControllerTests.TestClockConfig.class)
class AdminControllerTests extends EventIntegrationTest {

    static final Instant NOW = Instant.parse("2026-12-01T00:00:00Z");
    static final Instant OPEN = NOW.plus(Duration.ofHours(2));
    static final Instant START = OPEN.plus(Duration.ofDays(7));

    @TestConfiguration
    static class TestClockConfig {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(NOW);
        }
    }

    static class MutableClock extends Clock {
        private volatile Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void set(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("Asia/Seoul");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Autowired
    TestRestTemplate rest;

    @Autowired
    MutableClock clock;

    @Autowired
    ObjectMapper mapper;

    long performanceId;

    @BeforeEach
    void seed() {
        clock.set(NOW);
        jdbc.update(
                "INSERT INTO hall (mt13id, mt10id, name, seat_scale, disabled_seat_scale) VALUES ('H-01', 'F1', 'h', 100, 2)");
        jdbc.update(
                "INSERT INTO performance (mt20id, name, mt10id, mt13id, start_date, end_date, detail_status, created_at, updated_at)"
                        + " VALUES ('PF1', '공연', 'F1', 'H-01', '2026-12-01', '2026-12-31', 'DONE', NOW(6), NOW(6))");
        jdbc.update(
                "INSERT INTO performance (mt20id, name, mt10id, mt13id, start_date, end_date, detail_status, created_at, updated_at)"
                        + " VALUES ('PF2', '대기', 'F1', NULL, '2026-12-01', '2026-12-31', 'PENDING', NOW(6), NOW(6))");
        performanceId = register("PF1").getBody().get("id").asLong();
    }

    ResponseEntity<JsonNode> call(String role, HttpMethod method, String path, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (role != null) {
            headers.set("X-User-Role", role);
        }
        return rest.exchange(path, method, new HttpEntity<>(body, headers), JsonNode.class);
    }

    ResponseEntity<JsonNode> admin(HttpMethod method, String path, Object body) {
        return call("ADMIN", method, path, body);
    }

    ResponseEntity<JsonNode> register(String kopisId) {
        return admin(HttpMethod.POST, "/api/v1/admin/performances", Map.of("kopisId", kopisId));
    }

    Map<String, Object> sessionBody(Instant open) {
        return Map.of(
                "startAt", iso(START),
                "saleOpenAt", iso(open),
                "prices", List.of(Map.of("grade", "R", "price", 120000), Map.of("grade", "S", "price", 90000)),
                "admissionPerSecond", 50,
                "maxActiveUsers", 500);
    }

    static String iso(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.ofHours(9)).toString();
    }

    long createSession(Instant open) {
        ResponseEntity<JsonNode> response =
                admin(HttpMethod.POST, "/api/v1/admin/performances/" + performanceId + "/sessions", sessionBody(open));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody().get("sessionId").asLong();
    }

    List<String> outboxTypes() {
        return jdbc.queryForList("SELECT event_type FROM outbox ORDER BY id", String.class);
    }

    JsonNode lastOutboxPayload() throws Exception {
        String json = jdbc.queryForObject("SELECT payload FROM outbox ORDER BY id DESC LIMIT 1", String.class);
        return mapper.readTree(json).get("payload");
    }

    @Test
    @DisplayName("ADMIN이 아니면 관리자 API는 모두 403이다")
    void nonAdminForbidden() {
        for (String role : new String[] {null, "USER"}) {
            assertThat(call(role, HttpMethod.POST, "/api/v1/admin/performances", Map.of("kopisId", "PF1"))
                            .getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(call(role, HttpMethod.DELETE, "/api/v1/admin/sessions/1", null)
                            .getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN);
            ResponseEntity<JsonNode> sync = call(role, HttpMethod.POST, "/api/v1/admin/kopis/sync", null);
            assertThat(sync.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(sync.getBody().get("code").asText()).isEqualTo("FORBIDDEN");
        }
    }

    @Test
    @DisplayName("공연 등록은 처음 201, 다시 하면 같은 ID로 200이고 동기화되지 않은 공연은 409, 없는 공연은 404다")
    void registerPerformance() {
        ResponseEntity<JsonNode> again = register("PF1");
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(again.getBody().get("id").asLong()).isEqualTo(performanceId);
        assertThat(register("PF2").getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(register("PF2").getBody().get("code").asText()).isEqualTo("PERFORMANCE_NOT_SYNCED");
        assertThat(register("NOPE").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM hall_seat WHERE mt13id = 'H-01'", Integer.class))
                .isEqualTo(100);
    }

    @Test
    @DisplayName("회차를 등록하면 SessionCreated가 전체 값으로 outbox에 쌓이고 시각은 오프셋을 포함한 ISO-8601이다")
    void createPublishesEvent() throws Exception {
        long id = createSession(OPEN);

        assertThat(outboxTypes()).containsExactly("SessionCreated");
        JsonNode payload = lastOutboxPayload();
        assertThat(payload.get("sessionId").asLong()).isEqualTo(id);
        assertThat(payload.get("performanceId").asLong()).isEqualTo(performanceId);
        assertThat(payload.get("venueId").asText()).isEqualTo("H-01");
        assertThat(OffsetDateTime.parse(payload.get("startAt").asText()).toInstant())
                .isEqualTo(START);
        assertThat(payload.get("startAt").asText()).endsWith("+09:00");
        assertThat(OffsetDateTime.parse(payload.get("saleOpenAt").asText()).toInstant())
                .isEqualTo(OPEN);
        assertThat(payload.get("prices")).hasSize(2);
        assertThat(payload.get("admissionPerSecond").asInt()).isEqualTo(50);
        assertThat(payload.get("maxActiveUsers").asInt()).isEqualTo(500);
        assertThat(payload.has("seats")).isFalse();
        assertThat(jdbc.queryForObject("SELECT message_key FROM outbox", String.class))
                .isEqualTo(String.valueOf(id));
    }

    @Test
    @DisplayName("회차 등록 값 검증: 오픈이 과거, 시작이 오픈보다 앞, 등급 중복, 가격 목록 없음은 400이고 이벤트가 없다")
    void createValidation() {
        String path = "/api/v1/admin/performances/" + performanceId + "/sessions";
        assertThat(admin(HttpMethod.POST, path, sessionBody(NOW.minusSeconds(1)))
                        .getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(admin(HttpMethod.POST, path, sessionBody(START.plusSeconds(1)))
                        .getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        Map<String, Object> dup = new java.util.HashMap<>(sessionBody(OPEN));
        dup.put("prices", List.of(Map.of("grade", "R", "price", 1000), Map.of("grade", "R", "price", 2000)));
        assertThat(admin(HttpMethod.POST, path, dup).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        dup.put("prices", List.of());
        assertThat(admin(HttpMethod.POST, path, dup).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(admin(HttpMethod.POST, "/api/v1/admin/performances/999/sessions", sessionBody(OPEN))
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(outboxTypes()).isEmpty();
    }

    @Test
    @DisplayName("오픈 10분 전보다 이른 시각에는 일정·가격도 수정되고 SessionUpdated가 바뀐 뒤 전체 값을 싣는다")
    void updateBeforeLock() throws Exception {
        long id = createSession(OPEN);
        clock.set(OPEN.minus(Duration.ofMinutes(10)).minusMillis(1));

        ResponseEntity<JsonNode> response = admin(
                HttpMethod.PATCH,
                "/api/v1/admin/sessions/" + id,
                Map.of("saleOpenAt", iso(OPEN.plus(Duration.ofHours(1))), "admissionPerSecond", 80));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(outboxTypes()).containsExactly("SessionCreated", "SessionUpdated");
        JsonNode payload = lastOutboxPayload();
        assertThat(payload.get("admissionPerSecond").asInt()).isEqualTo(80);
        assertThat(payload.get("maxActiveUsers").asInt()).isEqualTo(500);
        assertThat(payload.get("prices")).hasSize(2);
        assertThat(OffsetDateTime.parse(payload.get("saleOpenAt").asText()).toInstant())
                .isEqualTo(OPEN.plus(Duration.ofHours(1)));
    }

    @Test
    @DisplayName("오픈 10분 전 정각부터는 일정·가격 수정이 409이고 입장 속도·활성 상한만 바뀐다")
    void updateLockBoundary() throws Exception {
        long id = createSession(OPEN);
        String path = "/api/v1/admin/sessions/" + id;
        clock.set(OPEN.minus(Duration.ofMinutes(10)));

        ResponseEntity<JsonNode> locked =
                admin(HttpMethod.PATCH, path, Map.of("saleOpenAt", iso(OPEN.plusSeconds(3600))));
        assertThat(locked.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(locked.getBody().get("code").asText()).isEqualTo("SESSION_EDIT_LOCKED");
        assertThat(admin(HttpMethod.PATCH, path, Map.of("prices", List.of(Map.of("grade", "R", "price", 1))))
                        .getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(outboxTypes()).containsExactly("SessionCreated");

        ResponseEntity<JsonNode> throttle =
                admin(HttpMethod.PATCH, path, Map.of("admissionPerSecond", 10, "maxActiveUsers", 100));
        assertThat(throttle.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(lastOutboxPayload().get("maxActiveUsers").asInt()).isEqualTo(100);

        // 오픈 뒤에도 같다
        clock.set(OPEN.plusSeconds(30));
        assertThat(admin(HttpMethod.PATCH, path, Map.of("admissionPerSecond", 5))
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(admin(HttpMethod.PATCH, path, Map.of("startAt", iso(START.plusSeconds(60))))
                        .getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("오픈 시각을 이미 잠긴 구간 안으로 옮기는 수정은 400이고, 빈 수정도 400이다")
    void updateCannotMoveIntoLockWindow() {
        long id = createSession(OPEN);
        String path = "/api/v1/admin/sessions/" + id;
        assertThat(admin(HttpMethod.PATCH, path, Map.of("saleOpenAt", iso(NOW.plus(Duration.ofMinutes(9)))))
                        .getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(admin(HttpMethod.PATCH, path, Map.of()).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(admin(HttpMethod.PATCH, "/api/v1/admin/sessions/999", Map.of("admissionPerSecond", 1))
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("오픈 전에는 삭제되고 SessionDeleted가 쌓이며, 오픈 시각 정각부터는 409 SESSION_ALREADY_OPENED다")
    void deleteBeforeAndAfterOpen() {
        long early = createSession(OPEN);
        long late = createSession(OPEN);

        clock.set(OPEN.minusMillis(1));
        assertThat(admin(HttpMethod.DELETE, "/api/v1/admin/sessions/" + early, null)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM session WHERE id = ?", Integer.class, early))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM session_price WHERE session_id = ?", Integer.class, early))
                .isZero();
        assertThat(outboxTypes()).containsExactly("SessionCreated", "SessionCreated", "SessionDeleted");

        clock.set(OPEN);
        ResponseEntity<JsonNode> opened = admin(HttpMethod.DELETE, "/api/v1/admin/sessions/" + late, null);
        assertThat(opened.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(opened.getBody().get("code").asText()).isEqualTo("SESSION_ALREADY_OPENED");
        assertThat(outboxTypes()).hasSize(3);
    }

    @Test
    @DisplayName("판매 중지는 SessionSuspended를 한 번만 쌓는다")
    void suspendIsIdempotent() throws Exception {
        long id = createSession(OPEN);
        clock.set(OPEN.plusSeconds(60));
        String path = "/api/v1/admin/sessions/" + id + "/suspend";

        assertThat(admin(HttpMethod.POST, path, null).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(admin(HttpMethod.POST, path, null).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(outboxTypes()).containsExactly("SessionCreated", "SessionSuspended");
        assertThat(lastOutboxPayload().get("sessionId").asLong()).isEqualTo(id);
        assertThat(admin(HttpMethod.POST, "/api/v1/admin/sessions/999/suspend", null)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("KOPIS 수동 동기화는 202와 실행 ID를 돌려준다(fixture 모드)")
    void manualSync() {
        ResponseEntity<JsonNode> response = admin(HttpMethod.POST, "/api/v1/admin/kopis/sync", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        long runId = response.getBody().get("runId").asLong();
        assertThat(jdbc.queryForObject("SELECT trigger_type FROM kopis_sync_run WHERE id = ?", String.class, runId))
                .isEqualTo("MANUAL");
    }
}
