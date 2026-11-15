package io.github.kanabig.curtaincall.event.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.kanabig.curtaincall.event.FakeKopisServer;
import io.github.kanabig.curtaincall.event.client.KopisXmlParser;
import io.github.kanabig.curtaincall.event.dto.PriceTable;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** EVT-7: pcseguidance 파싱과 기본 등급표 fallback. */
class PriceGuidanceParserTests {

    private final PriceGuidanceParser parser = new PriceGuidanceParser();
    private final KopisXmlParser xml = new KopisXmlParser();

    @ParameterizedTest
    @ValueSource(
            strings = {
                "PF305720:1층석:15000",
                "PF305718:전석:10000",
                "PF305735:전석:60000",
            })
    @DisplayName("저장한 실제 응답(공연상세 fixture)의 등급형 가격을 읽는다")
    void parsesFixtureResponses(String spec) {
        String[] parts = spec.split(":");
        String guidance = xml.parseDetail(FakeKopisServer.fixture("pblprfr-" + parts[0] + ".xml"))
                .priceGuidance();

        PriceTable table = parser.parse(guidance);

        assertThat(table.parsed()).isTrue();
        assertThat(table.tiers()).containsExactly(new PriceTable.Tier(parts[1], Integer.parseInt(parts[2])));
    }

    @Test
    @DisplayName("등급이 여럿이면 비싼 등급부터 정렬하고 depth로 등급을 고른다")
    void multipleGrades() {
        PriceTable table = parser.parse("VIP석 150,000원, R석 120,000원 / S석 90,000원, A석 60,000원(할인 제외)");

        assertThat(table.parsed()).isTrue();
        assertThat(table.tiers()).extracting(PriceTable.Tier::grade).containsExactly("VIP석", "R석", "S석", "A석");
        assertThat(table.tierAt(0).grade()).isEqualTo("VIP석");
        assertThat(table.tierAt(260).grade()).isEqualTo("R석");
        assertThat(table.tierAt(999).grade()).isEqualTo("A석");
    }

    @Test
    @DisplayName("입력 순서가 달라도 비싼 등급이 앞이다")
    void sortsByPrice() {
        PriceTable table = parser.parse("S석 90,000원, R석 120,000원");

        assertThat(table.tiers()).extracting(PriceTable.Tier::price).containsExactly(120_000, 90_000);
        assertThat(table.tierAt(499).grade()).isEqualTo("R석");
        assertThat(table.tierAt(500).grade()).isEqualTo("S석");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                // 실제 응답에서 확인한 비등급형
                "현장판매 14,900원, 사전예약 9,900원",
                "현장판매 14,900원",
                "성인 20,000원, 청소년 10,000원",
                "전석 무료",
                "무료",
                "R석 99,000원, 현장판매 14,900원",
                "R석 99,000원, S석 미정",
                "R석 99,000원, R석 80,000원",
                "R석 500원",
                "문의 02-123-4567",
            })
    @DisplayName("등급이 아니거나 읽지 못하는 항목이 하나라도 있으면 기본 등급표")
    void fallsBackOnNonGradeText(String guidance) {
        PriceTable table = parser.parse(guidance);

        assertThat(table.parsed()).isFalse();
        assertThat(table).isEqualTo(PriceTable.defaults());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\n"})
    @DisplayName("빈 값은 기본 등급표")
    void fallsBackOnBlank(String guidance) {
        assertThat(parser.parse(guidance)).isEqualTo(PriceTable.defaults());
    }

    @Test
    @DisplayName("기본 등급표는 4개 등급이고 가격이 내려간다")
    void defaultTable() {
        List<PriceTable.Tier> tiers = PriceTable.defaults().tiers();

        assertThat(tiers).hasSize(4);
        assertThat(tiers).isSortedAccordingTo((a, b) -> Integer.compare(b.price(), a.price()));
        assertThat(PriceTable.defaults().tierAt(0).grade()).isEqualTo("VIP");
        assertThat(PriceTable.defaults().tierAt(999).grade()).isEqualTo("A");
    }
}
