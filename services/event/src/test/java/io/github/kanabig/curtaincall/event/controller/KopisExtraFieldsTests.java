package io.github.kanabig.curtaincall.event.controller;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.kanabig.curtaincall.event.EventIntegrationTest;
import io.github.kanabig.curtaincall.event.service.KopisSyncService;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.JsonNode;

/** EVT-4, EVT-5: fixture 3건을 동기화해 추가 항목이 저장되고 조회되는지 본다. */
@AutoConfigureTestRestTemplate
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "curtaincall.kopis.scheduler.enabled=false",
            "curtaincall.kopis.bootstrap=false",
            "curtaincall.outbox.relay.auto-start=false"
        })
class KopisExtraFieldsTests extends EventIntegrationTest {

    @Autowired
    TestRestTemplate rest;

    @Autowired
    KopisSyncService sync;

    @BeforeEach
    void syncFixtureAndRegister() {
        // BOOTSTRAP은 끝난 공연의 상세도 받아 날짜에 기대지 않는다
        sync.sync("BOOTSTRAP");
        for (String id : List.of("PF305718", "PF305720", "PF305735")) {
            jdbc.update("INSERT INTO sale_performance (mt20id, created_at) VALUES (?, NOW(6))", id);
        }
    }

    @Test
    @DisplayName("상세: 출연진·제작사·소개 이미지·하위 분류·공연장 시설")
    void detail() {
        JsonNode child = get("/api/v1/performances/" + saleId("PF305718"));
        assertThat(texts(child.get("categories"))).containsExactly("CHILD");
        assertThat(child.get("cast").isNull()).isTrue();
        assertThat(child.get("producer").asText()).isEqualTo("춘천시립인형극단");
        assertThat(texts(child.get("introImages")))
                .containsExactly("http://www.kopis.or.kr/upload/pfmIntroImage/PF_PF305718_202611091118314300.png");
        JsonNode venue = child.get("venue");
        assertThat(venue.get("phone").asText()).isEqualTo("033-242-8450");
        assertThat(venue.get("website").asText())
                .isEqualTo("http://www.cccf.or.kr/designer/skin/cafacility/puppetmain.html");
        assertThat(venue.get("parkingLot").asBoolean()).isTrue();
        assertThat(venue.get("accessibility").get("elevator").asBoolean()).isFalse();
        assertThat(venue.get("accessibility").get("parking").asBoolean()).isTrue();
        assertThat(venue.get("accessibility").get("restroom").asBoolean()).isTrue();
        assertThat(venue.get("accessibility").get("ramp").asBoolean()).isTrue();
        assertThat(venue.get("amenities").get("cafe").asBoolean()).isTrue();
        assertThat(venue.get("amenities").get("restaurant").asBoolean()).isFalse();
        assertThat(venue.get("amenities").get("store").asBoolean()).isFalse();
        assertThat(venue.get("amenities").get("playroom").asBoolean()).isFalse();
        assertThat(venue.get("amenities").get("nursingRoom").asBoolean()).isFalse();

        JsonNode creative = get("/api/v1/performances/" + saleId("PF305735"));
        assertThat(texts(creative.get("categories"))).containsExactly("CREATIVE");
        assertThat(creative.get("cast").asText()).startsWith("권오현, 김찬우");
    }

    @Test
    @DisplayName("목록: 하위 분류 조건은 여러 값이 OR이고 응답에 싣는다")
    void listByCategory() {
        assertThat(ids("?category=CHILD")).containsExactly("PF305718");
        assertThat(ids("?category=CREATIVE")).containsExactly("PF305735");
        assertThat(ids("?category=CHILD&category=CREATIVE")).containsExactlyInAnyOrder("PF305718", "PF305735");
        assertThat(ids("?category=CHILD,CREATIVE&region=서울특별시")).containsExactly("PF305735");
        assertThat(ids("?category=VISIT,LICENSE,DAEHAKRO")).isEmpty();
        JsonNode item =
                get("/api/v1/performances?category=CHILD").get("content").get(0);
        assertThat(texts(item.get("categories"))).containsExactly("CHILD");
    }

    private List<String> ids(String query) {
        List<String> out = new ArrayList<>();
        get("/api/v1/performances" + query)
                .get("content")
                .forEach(n -> out.add(jdbc.queryForObject(
                        "SELECT mt20id FROM sale_performance WHERE id = ?",
                        String.class,
                        n.get("id").asLong())));
        return out;
    }

    private long saleId(String mt20id) {
        return jdbc.queryForObject("SELECT id FROM sale_performance WHERE mt20id = ?", Long.class, mt20id);
    }

    private JsonNode get(String path) {
        return rest.getForObject(path, JsonNode.class);
    }

    private static List<String> texts(JsonNode array) {
        return array.valueStream().map(JsonNode::asString).toList();
    }
}
