package io.github.kanabig.curtaincall.event.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.kanabig.curtaincall.event.EventIntegrationTest;
import io.github.kanabig.curtaincall.event.client.KopisCallException;
import io.github.kanabig.curtaincall.event.client.KopisClient;
import io.github.kanabig.curtaincall.event.dto.PerformanceQueryDtos.Filter;
import io.github.kanabig.curtaincall.event.repository.PerformanceQueryRepository;
import io.github.kanabig.curtaincall.event.repository.PerformanceQueryRepository.Built;
import io.github.kanabig.curtaincall.event.service.KopisSyncService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
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
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** EVT-4, EVT-5 공연·회차 조회. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "curtaincall.kopis.scheduler.enabled=false",
            "curtaincall.kopis.bootstrap=false",
            "curtaincall.outbox.relay.auto-start=false"
        })
@Import(PerformanceQueryTests.FixedClockConfig.class)
class PerformanceQueryTests extends EventIntegrationTest {

    static final Instant NOW = Instant.parse("2026-12-01T00:00:00Z");

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(NOW, ZoneId.of("Asia/Seoul"));
        }
    }

    @Autowired
    TestRestTemplate rest;

    @Autowired
    PerformanceQueryRepository repository;

    @MockitoBean
    KopisClient kopisClient;

    @MockitoBean
    KopisSyncService sync;

    @BeforeEach
    void reset() {
        Mockito.reset(kopisClient, sync);
    }

    /** 공연을 KOPIS 동기화 결과로 넣고 판매 등록한다. 판매 등록 ID를 돌려준다. */
    long sale(String id, String name, String genre, String area, String state, String start, String end) {
        jdbc.update(
                "INSERT INTO performance (mt20id, name, mt10id, mt13id, facility_name, genre, area, state, start_date,"
                        + " end_date, detail_status, created_at, updated_at)"
                        + " VALUES (?, ?, 'F1', 'H-01', '시설', ?, ?, ?, ?, ?, 'DONE', NOW(6), NOW(6))",
                id,
                name,
                genre,
                area,
                state,
                start,
                end);
        jdbc.update("INSERT INTO sale_performance (mt20id, created_at) VALUES (?, NOW(6))", id);
        return jdbc.queryForObject("SELECT id FROM sale_performance WHERE mt20id = ?", Long.class, id);
    }

    long session(long performanceId, String startAt, String openAt, boolean suspended, Map<String, Integer> prices) {
        jdbc.update(
                "INSERT INTO session (performance_id, start_at, sale_open_at, admission_per_second, max_active_users,"
                        + " suspended, created_at, updated_at) VALUES (?, ?, ?, 100, 1000, ?, NOW(6), NOW(6))",
                performanceId,
                startAt,
                openAt,
                suspended);
        long id = jdbc.queryForObject("SELECT MAX(id) FROM session", Long.class);
        prices.forEach((g, p) ->
                jdbc.update("INSERT INTO session_price (session_id, grade, price) VALUES (?, ?, ?)", id, g, p));
        return id;
    }

    JsonNode get(String path, Object... vars) {
        ResponseEntity<JsonNode> r = rest.getForEntity(path, JsonNode.class, vars);
        assertThat(r.getStatusCode()).as(path).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    List<String> names(JsonNode page) {
        List<String> out = new ArrayList<>();
        page.get("content").forEach(n -> out.add(n.get("name").asText()));
        return out;
    }

    @Test
    @DisplayName("지역·장르·기간·공연 상태·키워드 필터와 조합")
    void filters() {
        sale("A", "오페라의 유령", "뮤지컬", "서울", "공연중", "2026-11-01", "2027-01-31");
        sale("B", "레미제라블", "뮤지컬", "부산", "공연예정", "2027-02-01", "2027-03-31");
        sale("C", "백조의 호수", "무용", "서울", "공연예정", "2027-01-10", "2027-01-20");
        // 등록되지 않은 공연은 목록에 없다
        jdbc.update(
                "INSERT INTO performance (mt20id, name, genre, area, state, start_date, end_date, created_at, updated_at)"
                        + " VALUES ('X', '미등록', '뮤지컬', '서울', '공연중', '2026-11-01', '2027-01-31', NOW(6), NOW(6))");

        assertThat(names(get("/api/v1/performances"))).containsExactly("오페라의 유령", "백조의 호수", "레미제라블");
        assertThat(get("/api/v1/performances").get("totalElements").asLong()).isEqualTo(3);
        assertThat(names(get("/api/v1/performances?region=서울"))).containsExactly("오페라의 유령", "백조의 호수");
        assertThat(names(get("/api/v1/performances?genre=뮤지컬"))).containsExactly("오페라의 유령", "레미제라블");
        assertThat(names(get("/api/v1/performances?state=공연예정"))).containsExactly("백조의 호수", "레미제라블");
        // 기간은 겹치는 공연: 2027-01-15~2027-01-16 에는 A(~1/31)와 C(1/10~1/20)
        assertThat(names(get("/api/v1/performances?from=2027-01-15&to=2027-01-16")))
                .containsExactly("오페라의 유령", "백조의 호수");
        assertThat(names(get("/api/v1/performances?from=2027-03-01"))).containsExactly("레미제라블");
        assertThat(names(get("/api/v1/performances?keyword=호수"))).containsExactly("백조의 호수");
        assertThat(names(get("/api/v1/performances?region=서울&genre=뮤지컬&state=공연중&keyword=유령")))
                .containsExactly("오페라의 유령");
        assertThat(names(get("/api/v1/performances?region=부산&genre=무용"))).isEmpty();
    }

    @Test
    @DisplayName("키워드의 % _ \\ 는 문자 그대로 비교한다")
    void keywordWildcardsAreLiteral() {
        sale("A", "100% 라이브", "콘서트", "서울", "공연중", "2026-11-01", "2027-01-31");
        sale("B", "1000 라이브", "콘서트", "서울", "공연중", "2026-11-01", "2027-01-31");
        sale("C", "a_b", "콘서트", "서울", "공연중", "2026-11-01", "2027-01-31");
        sale("D", "axb", "콘서트", "서울", "공연중", "2026-11-01", "2027-01-31");

        assertThat(names(get("/api/v1/performances?keyword={k}", "100%"))).containsExactly("100% 라이브");
        assertThat(names(get("/api/v1/performances?keyword={k}", "a_b"))).containsExactly("a_b");
    }

    @Test
    @DisplayName("maxPrice는 판매할 수 있는 회차의 최저 등급가 기준이고 중지·종료 회차는 보지 않는다")
    void maxPrice() {
        long cheap = sale("A", "싼 공연", "뮤지컬", "서울", "공연중", "2026-11-01", "2027-01-31");
        long pricey = sale("B", "비싼 공연", "뮤지컬", "서울", "공연중", "2026-11-01", "2027-01-31");
        long suspended = sale("C", "중지된 공연", "뮤지컬", "서울", "공연중", "2026-11-01", "2027-01-31");
        long past = sale("D", "지난 공연", "뮤지컬", "서울", "공연중", "2026-11-01", "2027-01-31");
        sale("E", "회차 없는 공연", "뮤지컬", "서울", "공연중", "2026-11-01", "2027-01-31");
        session(cheap, "2026-12-20 19:00:00", "2026-12-01 10:00:00", false, Map.of("VIP", 150000, "S", 50000));
        session(pricey, "2026-12-20 19:00:00", "2026-12-01 10:00:00", false, Map.of("VIP", 200000, "S", 90000));
        session(suspended, "2026-12-20 19:00:00", "2026-12-01 10:00:00", true, Map.of("S", 10000));
        session(past, "2026-11-20 19:00:00", "2026-11-01 10:00:00", false, Map.of("S", 10000));

        assertThat(names(get("/api/v1/performances?maxPrice=60000"))).containsExactly("싼 공연");
        assertThat(names(get("/api/v1/performances?maxPrice=100000"))).containsExactly("싼 공연", "비싼 공연");
        assertThat(names(get("/api/v1/performances?maxPrice=1000"))).isEmpty();

        JsonNode page = get("/api/v1/performances");
        assertThat(page.get("content").get(0).get("minPrice").asInt()).isEqualTo(50000);
        assertThat(page.get("content").get(1).get("minPrice").asInt()).isEqualTo(90000);
        assertThat(page.get("content").get(2).get("minPrice").isNull()).isTrue();
    }

    @Test
    @DisplayName("페이지네이션과 범위 검사")
    void paging() {
        for (int i = 0; i < 5; i++) {
            sale("P" + i, "공연" + i, "뮤지컬", "서울", "공연중", "2026-11-0" + (i + 1), "2027-01-31");
        }
        JsonNode second = get("/api/v1/performances?page=1&size=2");
        assertThat(names(second)).containsExactly("공연2", "공연3");
        assertThat(second.get("page").asInt()).isEqualTo(1);
        assertThat(second.get("size").asInt()).isEqualTo(2);
        assertThat(second.get("totalElements").asLong()).isEqualTo(5);
        assertThat(get("/api/v1/performances?size=100").get("content")).hasSize(5);

        for (String bad : List.of("size=101", "size=0", "page=-1", "from=2027-02-01&to=2027-01-01", "maxPrice=-1")) {
            ResponseEntity<JsonNode> r = rest.getForEntity("/api/v1/performances?" + bad, JsonNode.class);
            assertThat(r.getStatusCode()).as(bad).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(r.getBody().get("code").asText()).isEqualTo("INVALID_REQUEST");
        }
    }

    @Test
    @DisplayName("상세: KOPIS 정보와 판매할 수 있는 회차, 등급별 가격. 중지·종료 회차는 빠진다")
    void detail() {
        long id = sale("A", "오페라의 유령", "뮤지컬", "서울", "공연중", "2026-11-01", "2027-01-31");
        jdbc.update(
                "UPDATE performance SET relates = '[{\"name\":\"예매처\",\"url\":\"http://x\"}]', runtime = '2시간' WHERE mt20id = 'A'");
        long later =
                session(id, "2026-12-25 19:00:00", "2026-12-10 10:00:00", false, Map.of("VIP", 150000, "S", 80000));
        long open = session(id, "2026-12-20 19:00:00", "2026-11-30 10:00:00", false, Map.of("S", 70000));
        session(id, "2026-12-22 19:00:00", "2026-11-30 10:00:00", true, Map.of("S", 70000));
        session(id, "2026-11-25 19:00:00", "2026-11-01 10:00:00", false, Map.of("S", 70000));

        JsonNode d = get("/api/v1/performances/" + id);
        assertThat(d.get("kopisId").asText()).isEqualTo("A");
        assertThat(d.get("name").asText()).isEqualTo("오페라의 유령");
        assertThat(d.get("runtime").asText()).isEqualTo("2시간");
        assertThat(d.get("relates").get(0).get("name").asText()).isEqualTo("예매처");
        JsonNode sessions = d.get("sessions");
        assertThat(sessions).hasSize(2);
        assertThat(sessions.get(0).get("id").asLong()).isEqualTo(open);
        assertThat(sessions.get(0).get("saleState").asText()).isEqualTo("ON_SALE");
        assertThat(sessions.get(1).get("id").asLong()).isEqualTo(later);
        assertThat(sessions.get(1).get("saleState").asText()).isEqualTo("SCHEDULED");
        assertThat(sessions.get(1).get("prices").get(0).get("grade").asText()).isEqualTo("VIP");
        assertThat(sessions.get(1).get("prices").get(0).get("price").asInt()).isEqualTo(150000);
        assertThat(sessions.get(1).get("startAt").asText()).isEqualTo("2026-12-25T19:00:00+09:00");

        assertThat(rest.getForEntity("/api/v1/performances/9999", JsonNode.class)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("회차 조회: 시각에 따라 SCHEDULED, ON_SALE, SUSPENDED, CLOSED")
    void sessionState() {
        long p = sale("A", "공연", "뮤지컬", "서울", "공연중", "2026-11-01", "2027-01-31");
        // 현재 2026-12-01T09:00+09:00(KST)
        long scheduled = session(p, "2026-12-20 19:00:00", "2026-12-05 10:00:00", false, Map.of("S", 1000));
        long onSale = session(p, "2026-12-20 19:00:00", "2026-11-30 10:00:00", false, Map.of("S", 1000));
        long suspended = session(p, "2026-12-20 19:00:00", "2026-11-30 10:00:00", true, Map.of("S", 1000));
        long closed = session(p, "2026-11-30 19:00:00", "2026-11-01 10:00:00", false, Map.of("S", 1000));

        assertThat(get("/api/v1/sessions/" + scheduled).get("saleState").asText())
                .isEqualTo("SCHEDULED");
        assertThat(get("/api/v1/sessions/" + onSale).get("saleState").asText()).isEqualTo("ON_SALE");
        assertThat(get("/api/v1/sessions/" + suspended).get("saleState").asText())
                .isEqualTo("SUSPENDED");
        JsonNode c = get("/api/v1/sessions/" + closed);
        assertThat(c.get("saleState").asText()).isEqualTo("CLOSED");
        assertThat(c.get("performanceId").asLong()).isEqualTo(p);
        assertThat(c.get("saleOpenAt").asText()).isEqualTo("2026-11-01T10:00:00+09:00");
        assertThat(rest.getForEntity("/api/v1/sessions/9999", JsonNode.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("조회는 인증 헤더 없이 되고 KOPIS 호출을 하지 않는다")
    void publicAndNoKopisCalls() {
        long id = sale("A", "공연", "뮤지컬", "서울", "공연중", "2026-11-01", "2027-01-31");
        get("/api/v1/performances?keyword=공연");
        get("/api/v1/performances/" + id);
        Mockito.verifyNoInteractions(kopisClient);
    }

    @Test
    @DisplayName("KOPIS가 실패하거나 동기화가 도는 중에도 조회는 마지막 동기화 결과를 돌려준다")
    void kopisFailureOrSyncInProgress() throws Exception {
        sale("A", "공연", "뮤지컬", "서울", "공연중", "2026-11-01", "2027-01-31");
        Mockito.when(kopisClient.listPerformances(
                        Mockito.any(), Mockito.any(), Mockito.anyInt(), Mockito.anyInt(), Mockito.any()))
                .thenThrow(new KopisCallException("5xx"));

        // 동기화가 끝나지 않은 채로 조회
        CountDownLatch release = new CountDownLatch(1);
        Mockito.doAnswer(inv -> {
                    inv.<java.util.function.LongConsumer>getArgument(1).accept(7L);
                    release.await(10, TimeUnit.SECONDS);
                    return null;
                })
                .when(sync)
                .sync(Mockito.eq("MANUAL"), Mockito.any());
        HttpHeaders admin = new HttpHeaders();
        admin.set("X-User-Role", "ADMIN");
        ResponseEntity<String> started =
                rest.exchange("/api/v1/admin/kopis/sync", HttpMethod.POST, new HttpEntity<>(admin), String.class);
        assertThat(started.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        assertThat(names(get("/api/v1/performances"))).containsExactly("공연");
        release.countDown();
    }

    @Test
    @DisplayName("목록·건수 쿼리의 EXPLAIN에 type=ALL이 없다")
    void explainHasNoFullScan() {
        // 옵티마이저는 작은 테이블이면 풀스캔을 고르므로 운영 규모에 가깝게 채운다: KOPIS 공연 6,000건 중 300건 판매 등록, 회차 900개
        String[] genres = {"뮤지컬", "연극", "무용", "콘서트", "클래식"};
        String[] areas = {"서울", "부산", "대구", "경기", "제주"};
        String[] states = {"공연예정", "공연중", "공연완료"};
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < 6000; i++) {
            LocalDate start = LocalDate.of(2026, 1, 1).plusDays(i % 500);
            rows.add(new Object[] {
                "E" + i, "공연 " + i, genres[i % 5], areas[(i / 5) % 5], states[i % 3], start, start.plusDays(30)
            });
        }
        jdbc.batchUpdate(
                "INSERT INTO performance (mt20id, name, genre, area, state, start_date, end_date, detail_status,"
                        + " created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, 'DONE', NOW(6), NOW(6))",
                rows);
        for (int i = 0; i < 6000; i += 20) {
            jdbc.update("INSERT INTO sale_performance (mt20id, created_at) VALUES (?, NOW(6))", "E" + i);
        }
        jdbc.update(
                "INSERT INTO session (performance_id, start_at, sale_open_at, admission_per_second, max_active_users,"
                        + " created_at, updated_at) SELECT id, '2027-01-01 19:00:00', '2026-12-01 10:00:00', 100, 1000,"
                        + " NOW(6), NOW(6) FROM sale_performance");
        jdbc.update("INSERT INTO session_price (session_id, grade, price) SELECT id, 'S', 50000 FROM session");
        for (String t : List.of("performance", "sale_performance", "session", "session_price")) {
            jdbc.execute("ANALYZE TABLE " + t);
        }

        List<Filter> filters = List.of(
                new Filter(null, null, null, null, null, null, null),
                new Filter("서울", null, null, null, null, null, null),
                new Filter(null, "뮤지컬", null, null, null, null, null),
                new Filter(null, null, LocalDate.of(2026, 6, 1), LocalDate.of(2026, 7, 1), null, null, null),
                new Filter(null, null, null, null, "공연중", null, null),
                new Filter(null, null, null, null, null, 60000, null),
                new Filter(null, null, null, null, null, null, "공연 1"),
                new Filter("서울", "뮤지컬", LocalDate.of(2026, 3, 1), null, "공연중", 60000, "공연"));
        for (Filter f : filters) {
            for (Built q : List.of(repository.listQuery(f, 0, 20, NOW), repository.countQuery(f, NOW))) {
                List<Map<String, Object>> plan =
                        jdbc.queryForList("EXPLAIN " + q.sql(), q.args().toArray());
                assertThat(plan)
                        .as("%s %s", f, q.sql())
                        .allSatisfy(r -> assertThat(r.get("type")).isNotEqualTo("ALL"));
            }
        }
    }
}
