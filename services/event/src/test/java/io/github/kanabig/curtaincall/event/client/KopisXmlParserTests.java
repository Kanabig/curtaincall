package io.github.kanabig.curtaincall.event.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.kanabig.curtaincall.event.FakeKopisServer;
import io.github.kanabig.curtaincall.event.dto.KopisFacility;
import io.github.kanabig.curtaincall.event.dto.KopisPerformanceDetail;
import io.github.kanabig.curtaincall.event.dto.KopisPerformanceSummary;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 저장해 둔 실제 KOPIS 응답으로 파싱을 확인한다. */
class KopisXmlParserTests {

    private final KopisXmlParser parser = new KopisXmlParser();

    @Test
    @DisplayName("공연목록: 식별자, 기간, 장르, 상태를 읽는다")
    void parsesList() {
        KopisXmlParser.ListResult result = parser.parseList(FakeKopisServer.fixture("pblprfr-list.xml"));

        assertThat(result.skipped()).isZero();
        assertThat(result.items()).hasSize(3);
        KopisPerformanceSummary first = result.items().get(0);
        assertThat(first.mt20id()).isEqualTo("PF305735");
        assertThat(first.startDate()).isEqualTo(LocalDate.of(2026, 11, 11));
        assertThat(first.endDate()).isEqualTo(LocalDate.of(2027, 1, 31));
        assertThat(first.genre()).isEqualTo("뮤지컬");
        assertThat(first.state()).isEqualTo("공연중");
        assertThat(first.openRun()).isFalse();
    }

    @Test
    @DisplayName("공연상세: 시설 ID, 가격·시간 안내 원문, 수정일, 예매처를 읽고 공백뿐인 항목은 null로 둔다")
    void parsesDetail() {
        KopisPerformanceDetail detail = parser.parseDetail(FakeKopisServer.fixture("pblprfr-PF305735.xml"));

        assertThat(detail.mt10id()).isEqualTo("FC001535");
        assertThat(detail.mt13id()).isEqualTo("FC001535-03");
        assertThat(detail.priceGuidance()).isEqualTo("전석 60,000원");
        assertThat(detail.dateGuidance()).contains("수요일 ~ 금요일(19:00)").contains("HOL(14:00)");
        assertThat(detail.updatedAt()).isEqualTo(LocalDateTime.of(2026, 11, 9, 15, 0, 3, 78_038_000));
        assertThat(detail.relates()).hasSize(2);
        assertThat(detail.relates().get(0).name()).isEqualTo("네이버N예약");
    }

    @Test
    @DisplayName("공연시설상세: 공연장별 좌석 규모를 읽고 비어 있는 장애인석 수는 null로 둔다")
    void parsesFacility() {
        KopisFacility facility = parser.parseFacility(FakeKopisServer.fixture("prfplc-FC001535.xml"));

        assertThat(facility.mt10id()).isEqualTo("FC001535");
        assertThat(facility.seatScale()).isEqualTo(286);
        assertThat(facility.latitude()).isNotNull();
        assertThat(facility.halls())
                .extracting(KopisFacility.Hall::mt13id)
                .containsExactly("FC001535-01", "FC001535-03");
        assertThat(facility.halls().get(0).seatScale()).isEqualTo(126);
        assertThat(facility.halls().get(0).disabledSeatScale()).isNull();
        // KOPIS가 공연장 좌석 수를 0으로 주는 경우. 값은 그대로 두고 좌석 생성 쪽에서 기본값을 정한다
        assertThat(facility.halls().get(1).seatScale()).isZero();
    }

    @Test
    @DisplayName("목록 항목 하나가 깨져도 나머지는 읽고 건너뛴 수를 알려 준다")
    void skipsBrokenListItem() {
        String xml = "<dbs><db><mt20id>PF1</mt20id><prfnm>가</prfnm><prfpdfrom>2026.11.11</prfpdfrom>"
                + "<prfpdto>2026.11.12</prfpdto></db><db><mt20id>PF2</mt20id><prfnm>나</prfnm>"
                + "<prfpdfrom>내일</prfpdfrom><prfpdto>2026.11.12</prfpdto></db></dbs>";

        KopisXmlParser.ListResult result = parser.parseList(xml);

        assertThat(result.items()).extracting(KopisPerformanceSummary::mt20id).containsExactly("PF1");
        assertThat(result.skipped()).isEqualTo(1);
    }

    @Test
    @DisplayName("깨진 XML, 빈 응답, 오류 응답(루트가 다름)은 파싱 예외")
    void rejectsUnusableResponses() {
        assertThatThrownBy(() -> parser.parseList("<dbs><db>")).isInstanceOf(KopisParseException.class);
        assertThatThrownBy(() -> parser.parseList("")).isInstanceOf(KopisParseException.class);
        assertThatThrownBy(() -> parser.parseList("<response><returncode>01</returncode></response>"))
                .isInstanceOf(KopisParseException.class);
        assertThatThrownBy(() -> parser.parseDetail(FakeKopisServer.EMPTY)).isInstanceOf(KopisParseException.class);
    }

    @Test
    @DisplayName("DTD가 있는 응답은 외부 엔티티를 읽지 않고 거부한다")
    void rejectsDoctype() {
        String xxe = "<?xml version=\"1.0\"?><!DOCTYPE dbs [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><dbs>&x;</dbs>";

        assertThatThrownBy(() -> parser.parseList(xxe)).isInstanceOf(KopisParseException.class);
    }
}
