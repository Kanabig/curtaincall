package io.github.kanabig.curtaincall.event.controller;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.kanabig.curtaincall.event.EventIntegrationTest;
import io.github.kanabig.curtaincall.event.scheduler.FixtureBootstrap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/**
 * ADM-1, EVT-6: 등록 후보 조회와 판매 불가 사유. fixture는 실제 KOPIS 응답이다.
 *
 * <ul>
 *   <li>PF305720(FC002228-01): 객석 수가 있어 판매 가능
 *   <li>PF305718(FC000680-09 춘천인형극박물관): seatscale 0. 고정 객석이 없는 공간으로 추정(미확인)
 *   <li>PF305735(FC001535-03 엠스테이지 2관): seatscale 0, 무대 면적도 없음. 정보가 빠진 것으로 추정(미확인)
 * </ul>
 */
@AutoConfigureTestRestTemplate
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"curtaincall.kopis.scheduler.enabled=false", "curtaincall.outbox.relay.auto-start=false"})
class RegistrationCandidateTests extends EventIntegrationTest {

    static final String CANDIDATES = "/api/v1/admin/performances/candidates";

    @Autowired
    TestRestTemplate rest;

    @Autowired
    FixtureBootstrap bootstrap;

    @BeforeEach
    void loadFixture() {
        bootstrap.run(null);
    }

    ResponseEntity<JsonNode> call(String role, HttpMethod method, String path, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (role != null) {
            headers.set("X-User-Role", role);
        }
        return rest.exchange(path, method, new HttpEntity<>(body, headers), JsonNode.class);
    }

    JsonNode candidates(String query) {
        return call("ADMIN", HttpMethod.GET, CANDIDATES + query, null).getBody();
    }

    JsonNode row(JsonNode page, String kopisId) {
        for (JsonNode item : page.get("content")) {
            if (item.get("kopisId").asText().equals(kopisId)) {
                return item;
            }
        }
        throw new AssertionError(kopisId + " 없음: " + page);
    }

    ResponseEntity<JsonNode> register(String kopisId) {
        return call("ADMIN", HttpMethod.POST, "/api/v1/admin/performances", Map.of("kopisId", kopisId));
    }

    @Test
    @DisplayName("판매 가능한 공연은 registrable, 객석 수 0인 실제 공연장 2곳은 HALL_NO_SEAT_SCALE이다")
    void realHallsWithZeroSeats() {
        JsonNode page = candidates("");

        assertThat(page.get("totalElements").asLong()).isEqualTo(3);
        assertThat(row(page, "PF305720").get("registrable").asBoolean()).isTrue();
        assertThat(row(page, "PF305720").get("reason").isNull()).isTrue();
        for (String blocked : new String[] {"PF305718", "PF305735"}) {
            assertThat(row(page, blocked).get("registrable").asBoolean()).isFalse();
            assertThat(row(page, blocked).get("reason").asText()).isEqualTo("HALL_NO_SEAT_SCALE");
        }
        assertThat(row(page, "PF305735").get("facilityName").asText()).contains("엠스테이지");
    }

    @Test
    @DisplayName("등록 API도 같은 사유 코드로 409를 주고, 후보 조회는 배치를 만들지 않는다")
    void registerRejectsWithSameReason() {
        for (String blocked : new String[] {"PF305718", "PF305735"}) {
            ResponseEntity<JsonNode> response = register(blocked);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(response.getBody().get("code").asText()).isEqualTo("HALL_NO_SEAT_SCALE");
        }
        candidates("");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM hall_layout", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sale_performance", Integer.class))
                .isZero();
    }

    @Test
    @DisplayName("상세 미동기화, 공연장 불명, 객석 수 상한 초과도 후보 사유와 등록 409 코드가 같다")
    void otherReasons() {
        jdbc.update(
                "INSERT INTO hall (mt13id, mt10id, name, seat_scale, disabled_seat_scale) VALUES ('BIG-01', 'F9', 'big', 20001, 0)");
        insert("PF-PENDING", "PENDING", "BIG-01");
        insert("PF-NOHALLID", "DONE", null);
        insert("PF-GHOST", "DONE", "GHOST-01"); // hall 행이 없는 공연장 ID
        insert("PF-BIG", "DONE", "BIG-01");

        JsonNode page = candidates("?size=100");

        assertThat(row(page, "PF-PENDING").get("reason").asText()).isEqualTo("PERFORMANCE_NOT_SYNCED");
        assertThat(row(page, "PF-NOHALLID").get("reason").asText()).isEqualTo("HALL_UNKNOWN");
        assertThat(row(page, "PF-GHOST").get("reason").asText()).isEqualTo("HALL_UNKNOWN");
        assertThat(row(page, "PF-BIG").get("reason").asText()).isEqualTo("HALL_SEAT_SCALE_TOO_LARGE");
        for (String id : new String[] {"PF-PENDING", "PF-NOHALLID", "PF-GHOST", "PF-BIG"}) {
            assertThat(register(id).getBody().get("code").asText())
                    .isEqualTo(row(page, id).get("reason").asText());
        }
    }

    @Test
    @DisplayName("등록한 공연은 registered와 performanceId를 주고, 배치가 만들어진 뒤에도 registrable이다")
    void registeredFlag() {
        assertThat(row(candidates(""), "PF305720").get("registered").asBoolean())
                .isFalse();
        long id = register("PF305720").getBody().get("id").asLong();

        JsonNode row = row(candidates(""), "PF305720");
        assertThat(row.get("registered").asBoolean()).isTrue();
        assertThat(row.get("performanceId").asLong()).isEqualTo(id);
        assertThat(row.get("registrable").asBoolean()).isTrue();
        assertThat(row(candidates(""), "PF305718").get("registered").asBoolean())
                .isFalse();
    }

    @Test
    @DisplayName("keyword와 페이지로 조회하고, 잘못된 page·size는 400, ADMIN이 아니면 403이다")
    void keywordAndPaging() {
        JsonNode byName = candidates("?keyword=" + "고인돌");
        assertThat(byName.get("totalElements").asLong()).isEqualTo(1);
        assertThat(byName.get("content").get(0).get("kopisId").asText()).isEqualTo("PF305718");
        assertThat(candidates("?keyword=%25").get("totalElements").asLong()).isZero(); // LIKE 와일드카드는 이스케이프

        JsonNode second = candidates("?size=2&page=1");
        assertThat(second.get("content")).hasSize(1);
        assertThat(second.get("totalElements").asLong()).isEqualTo(3);

        assertThat(call("ADMIN", HttpMethod.GET, CANDIDATES + "?size=0", null).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(call("ADMIN", HttpMethod.GET, CANDIDATES + "?page=-1", null).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        for (String role : new String[] {null, "USER"}) {
            assertThat(call(role, HttpMethod.GET, CANDIDATES, null).getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN);
        }
    }

    private void insert(String mt20id, String detailStatus, String mt13id) {
        jdbc.update(
                "INSERT INTO performance (mt20id, name, mt10id, mt13id, start_date, end_date, detail_status, created_at, updated_at)"
                        + " VALUES (?, ?, 'F9', ?, '2030-01-01', '2030-01-31', ?, NOW(6), NOW(6))",
                mt20id,
                mt20id,
                mt13id,
                detailStatus);
    }
}
