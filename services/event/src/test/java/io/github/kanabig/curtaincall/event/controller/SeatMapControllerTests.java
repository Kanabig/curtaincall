package io.github.kanabig.curtaincall.event.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.kanabig.curtaincall.event.EventIntegrationTest;
import io.github.kanabig.curtaincall.event.repository.HallSeatRepository;
import io.github.kanabig.curtaincall.event.repository.SessionRepository;
import io.github.kanabig.curtaincall.event.service.SeatMapService;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/** EVT-9, EVT-10. 좌석 배치도 조회와 캐시. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "curtaincall.kopis.scheduler.enabled=false",
            "curtaincall.kopis.bootstrap=false",
            "curtaincall.outbox.relay.auto-start=false"
        })
class SeatMapControllerTests extends EventIntegrationTest {

    @Autowired
    TestRestTemplate rest;

    @Autowired
    SeatMapService seatMaps;

    @MockitoSpyBean
    SessionRepository sessionRepository;

    @MockitoSpyBean
    HallSeatRepository hallSeatRepository;

    long performanceId;
    long sessionId;

    @BeforeEach
    void seed() {
        jdbc.update(
                "INSERT INTO hall (mt13id, mt10id, name, seat_scale, disabled_seat_scale) VALUES ('H-01', 'F1', 'h', 100, 2)");
        jdbc.update(
                "INSERT INTO performance (mt20id, name, mt10id, mt13id, start_date, end_date, detail_status, created_at, updated_at)"
                        + " VALUES ('PF1', '공연', 'F1', 'H-01', '2026-12-01', '2026-12-31', 'DONE', NOW(6), NOW(6))");
        performanceId = admin(HttpMethod.POST, "/api/v1/admin/performances", Map.of("kopisId", "PF1"))
                .getBody()
                .get("id")
                .asLong();
        sessionId = createSession();
        clearInvocations(sessionRepository, hallSeatRepository);
    }

    ResponseEntity<JsonNode> admin(HttpMethod method, String path, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-User-Role", "ADMIN");
        return rest.exchange(path, method, new HttpEntity<>(body, headers), JsonNode.class);
    }

    long createSession() {
        Instant open = Instant.now().plus(Duration.ofDays(1));
        Map<String, Object> body = Map.of(
                "startAt",
                iso(open.plus(Duration.ofDays(7))),
                "saleOpenAt",
                iso(open),
                "prices",
                AdminControllerTests.DEFAULT_PRICES,
                "admissionPerSecond",
                50,
                "maxActiveUsers",
                500);
        ResponseEntity<JsonNode> response =
                admin(HttpMethod.POST, "/api/v1/admin/performances/" + performanceId + "/sessions", body);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody().get("sessionId").asLong();
    }

    static String iso(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.ofHours(9)).toString();
    }

    ResponseEntity<JsonNode> get(String path, String ifNoneMatch) {
        HttpHeaders headers = new HttpHeaders();
        if (ifNoneMatch != null) {
            headers.set(HttpHeaders.IF_NONE_MATCH, ifNoneMatch);
        }
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
    }

    @Test
    @DisplayName("좌석 배치도는 좌석마다 위치·등급·가격·휠체어 여부를 담고 판매 상태는 담지 않으며, 가격 등급이 앞열부터 높다")
    void seatMapShape() {
        ResponseEntity<JsonNode> response = get("/api/v1/sessions/" + sessionId + "/seat-map", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode seats = response.getBody();
        assertThat(seats).hasSize(100);
        JsonNode first = seats.get(0);
        assertThat(first.fieldNames())
                .toIterable()
                .containsExactlyInAnyOrder(
                        "seatId", "floor", "section", "row", "number", "x", "y", "grade", "price", "wheelchair");
        assertThat(first.get("grade").asText()).isEqualTo("VIP");
        assertThat(first.get("price").asInt()).isEqualTo(150000);
        assertThat(seats.get(99).get("grade").asText()).isEqualTo("A");
        assertThat(seats.get(99).get("price").asInt()).isEqualTo(60000);
        Set<Long> ids = new HashSet<>();
        int wheelchair = 0;
        for (JsonNode seat : seats) {
            ids.add(seat.get("seatId").asLong());
            wheelchair += seat.get("wheelchair").asBoolean() ? 1 : 0;
        }
        assertThat(ids).hasSize(100);
        assertThat(wheelchair).isEqualTo(2);
        assertThat(response.getHeaders().getCacheControl())
                .contains("max-age=60")
                .contains("public");
        assertThat(response.getHeaders().getETag()).isNotBlank();
    }

    @Test
    @DisplayName("If-None-Match가 ETag와 같으면 304이고 본문이 없다. 다르면 200이다")
    void etagRevalidation() {
        String path = "/api/v1/sessions/" + sessionId + "/seat-map";
        String etag = get(path, null).getHeaders().getETag();

        ResponseEntity<JsonNode> notModified = get(path, etag);
        assertThat(notModified.getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED);
        assertThat(notModified.getBody()).isNull();
        assertThat(notModified.getHeaders().getETag()).isEqualTo(etag);
        assertThat(get(path, "W/" + etag).getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED);
        assertThat(get(path, "\"other\"").getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("Accept-Encoding: gzip이면 압축 본문과 인코딩별 ETag로 응답하고, 풀면 평문과 같다")
    void gzipVariant() throws Exception {
        String path = "/api/v1/sessions/" + sessionId + "/seat-map";
        HttpHeaders plainHeaders = new HttpHeaders();
        ResponseEntity<byte[]> plain =
                rest.exchange(path, HttpMethod.GET, new HttpEntity<>(plainHeaders), byte[].class);
        java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
        java.net.http.HttpResponse<byte[]> gz = client.send(
                java.net.http.HttpRequest.newBuilder(
                                rest.getRestTemplate().getUriTemplateHandler().expand(path))
                        .header("Accept-Encoding", "gzip")
                        .build(),
                java.net.http.HttpResponse.BodyHandlers.ofByteArray());

        assertThat(gz.headers().firstValue("Content-Encoding")).hasValue("gzip");
        assertThat(gz.headers().firstValue("Vary")).hasValue("Accept-Encoding");
        String gzEtag = gz.headers().firstValue("ETag").orElseThrow();
        assertThat(gzEtag).isNotEqualTo(plain.getHeaders().getETag());
        assertThat(gz.body().length).isLessThan(plain.getBody().length);
        try (var in = new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(gz.body()))) {
            assertThat(in.readAllBytes()).isEqualTo(plain.getBody());
        }
        assertThat(client.send(
                                java.net.http.HttpRequest.newBuilder(rest.getRestTemplate()
                                                .getUriTemplateHandler()
                                                .expand(path))
                                        .header("Accept-Encoding", "gzip")
                                        .header("If-None-Match", gzEtag)
                                        .build(),
                                java.net.http.HttpResponse.BodyHandlers.discarding())
                        .statusCode())
                .isEqualTo(304);
    }

    @Test
    @DisplayName("같은 회차를 다시 요청하면 DB를 읽지 않는다(첫 요청은 회차 1회·좌석 1회)")
    void servedFromCache() {
        String path = "/api/v1/sessions/" + sessionId + "/seat-map";

        get(path, null);
        verify(sessionRepository, times(1)).find(sessionId);
        verify(hallSeatRepository, times(1)).findSeatRows("H-01");
        clearInvocations(sessionRepository, hallSeatRepository);

        for (int i = 0; i < 5; i++) {
            assertThat(get(path, null).getStatusCode()).isEqualTo(HttpStatus.OK);
        }
        get("/internal/sessions/" + sessionId + "/seat-map", null);
        get(path, "\"x\"");
        verifyNoInteractions(sessionRepository, hallSeatRepository);
    }

    @Test
    @DisplayName("내부 API는 같은 좌석 목록을 주고 reservation이 읽는 seatId·grade·price를 포함한다")
    void internalSeatMap() {
        JsonNode pub = get("/api/v1/sessions/" + sessionId + "/seat-map", null).getBody();
        ResponseEntity<JsonNode> internal = get("/internal/sessions/" + sessionId + "/seat-map", null);

        assertThat(internal.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(internal.getBody()).isEqualTo(pub);
        assertThat(internal.getBody().get(0).has("seatId")).isTrue();
        assertThat(internal.getBody().get(0).get("grade").isTextual()).isTrue();
        assertThat(internal.getBody().get(0).get("price").isInt()).isTrue();
    }

    /** reservation의 SeatMapEntry(seatId, grade, price)와 같은 모양. 모든 좌석이 이 세 필드를 빠짐없이 가져야 한다. */
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    record ReservationSeat(long seatId, String grade, int price) {}

    @Autowired
    com.fasterxml.jackson.databind.ObjectMapper mapper;

    @Test
    @DisplayName("내부 API 응답의 모든 좌석이 reservation이 읽는 형식(seatId 숫자, grade 문자열, price 정수)이다")
    void internalSeatMapContract() {
        JsonNode seats =
                get("/internal/sessions/" + sessionId + "/seat-map", null).getBody();
        com.fasterxml.jackson.databind.ObjectMapper strict = mapper.copy()
                .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS);

        for (JsonNode seat : seats) {
            assertThat(seat.get("seatId").isIntegralNumber()).isTrue();
            assertThat(seat.get("grade").isTextual()).isTrue();
            assertThat(seat.get("price").isInt()).isTrue();
            ReservationSeat parsed = strict.convertValue(seat, ReservationSeat.class);
            assertThat(parsed.grade()).isNotBlank();
            assertThat(parsed.price()).isPositive();
        }
    }

    @Test
    @DisplayName("없는 회차는 404 SESSION_NOT_FOUND다")
    void unknownSession() {
        ResponseEntity<JsonNode> response = get("/api/v1/sessions/999999/seat-map", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().get("code").asText()).isEqualTo("SESSION_NOT_FOUND");
    }

    @Test
    @DisplayName("가격표를 수정하면 캐시가 비워져 새 가격과 새 ETag로 응답한다")
    void evictedOnPriceChange() {
        String path = "/api/v1/sessions/" + sessionId + "/seat-map";
        String before = get(path, null).getHeaders().getETag();

        List<Map<String, Object>> prices = List.of(
                Map.of("grade", "VIP", "price", 200000),
                Map.of("grade", "R", "price", 120000),
                Map.of("grade", "S", "price", 90000),
                Map.of("grade", "A", "price", 60000));
        assertThat(admin(HttpMethod.PATCH, "/api/v1/admin/sessions/" + sessionId, Map.of("prices", prices))
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);

        ResponseEntity<JsonNode> after = get(path, before);
        assertThat(after.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(after.getHeaders().getETag()).isNotEqualTo(before);
        assertThat(after.getBody().get(0).get("price").asInt()).isEqualTo(200000);
    }
}
