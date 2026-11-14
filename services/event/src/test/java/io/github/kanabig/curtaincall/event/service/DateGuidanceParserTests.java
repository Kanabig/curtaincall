package io.github.kanabig.curtaincall.event.service;

import static java.time.DayOfWeek.FRIDAY;
import static java.time.DayOfWeek.MONDAY;
import static java.time.DayOfWeek.SATURDAY;
import static java.time.DayOfWeek.SUNDAY;
import static java.time.DayOfWeek.THURSDAY;
import static java.time.DayOfWeek.TUESDAY;
import static java.time.DayOfWeek.WEDNESDAY;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.kanabig.curtaincall.event.FakeKopisServer;
import io.github.kanabig.curtaincall.event.client.KopisXmlParser;
import io.github.kanabig.curtaincall.event.dto.SessionRule;
import io.github.kanabig.curtaincall.event.dto.SessionRule.Slot;
import java.time.LocalTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** EVT-8: dtguidance 파싱과 기본 회차 규칙 fallback. */
class DateGuidanceParserTests {

    private final DateGuidanceParser parser = new DateGuidanceParser();
    private final KopisXmlParser xml = new KopisXmlParser();

    private String fixtureGuidance(String mt20id) {
        return xml.parseDetail(FakeKopisServer.fixture("pblprfr-" + mt20id + ".xml"))
                .dateGuidance();
    }

    @Test
    @DisplayName("실제 응답: 요일 범위 하나와 시각 하나")
    void dayRange() {
        SessionRule rule = parser.parse(fixtureGuidance("PF305720")); // 수요일 ~ 목요일(20:00)

        assertThat(rule.parsed()).isTrue();
        assertThat(rule.slots())
                .containsExactly(new Slot(WEDNESDAY, LocalTime.of(20, 0)), new Slot(THURSDAY, LocalTime.of(20, 0)));
    }

    @Test
    @DisplayName("실제 응답: 요일마다 시각이 다르다")
    void separateDays() {
        SessionRule rule = parser.parse(fixtureGuidance("PF305718")); // 수요일(10:30), 토요일(14:00)

        assertThat(rule.slots())
                .containsExactly(new Slot(WEDNESDAY, LocalTime.of(10, 30)), new Slot(SATURDAY, LocalTime.of(14, 0)));
    }

    @Test
    @DisplayName("실제 응답: 범위 둘과 공휴일(HOL). 공휴일 시각은 따로 둔다")
    void rangesWithHoliday() {
        SessionRule rule = parser.parse(fixtureGuidance("PF305735")); // 수요일 ~ 금요일(19:00), 토요일 ~ 일요일(14:00), HOL(14:00)

        assertThat(rule.parsed()).isTrue();
        assertThat(rule.slots())
                .containsExactly(
                        new Slot(WEDNESDAY, LocalTime.of(19, 0)),
                        new Slot(THURSDAY, LocalTime.of(19, 0)),
                        new Slot(FRIDAY, LocalTime.of(19, 0)),
                        new Slot(SATURDAY, LocalTime.of(14, 0)),
                        new Slot(SUNDAY, LocalTime.of(14, 0)));
        assertThat(rule.holidayTimes()).containsExactly(LocalTime.of(14, 0));
    }

    @Test
    @DisplayName("한 요일에 시각이 여럿이고, 요일만 있는 항목은 뒤따르는 시각을 같이 쓴다")
    void multipleTimesAndSharedTimes() {
        SessionRule rule = parser.parse("화요일 ~ 금요일(20:00), 토요일(14:00,18:00), 일요일, 공휴일(15:00)");

        assertThat(rule.parsed()).isTrue();
        assertThat(rule.slots())
                .contains(
                        new Slot(TUESDAY, LocalTime.of(20, 0)),
                        new Slot(SATURDAY, LocalTime.of(14, 0)),
                        new Slot(SATURDAY, LocalTime.of(18, 0)),
                        new Slot(SUNDAY, LocalTime.of(15, 0)))
                .hasSize(7);
        assertThat(rule.holidayTimes()).containsExactly(LocalTime.of(15, 0));
    }

    @Test
    @DisplayName("요일 이름 줄임, 일주일을 도는 범위, 평일·주말")
    void dayForms() {
        assertThat(parser.parse("금~월(19:30)").slots())
                .extracting(Slot::day)
                .containsExactly(FRIDAY, SATURDAY, SUNDAY, MONDAY);
        assertThat(parser.parse("평일(19:30), 주말(15:00)").slots()).hasSize(7);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "공연 시작 30분 전까지 입장",
                "수요일",
                "수요일(25:00)",
                "수요일(19:00, 오후)",
                "수요일(19:00), 다음주 확인",
                "수요일(19:00) 토요일(14:00)",
                "()",
                "상시",
            })
    @DisplayName("읽지 못하는 항목이 하나라도 있으면 기본 규칙")
    void fallsBackOnUnparsable(String guidance) {
        SessionRule rule = parser.parse(guidance);

        assertThat(rule.parsed()).isFalse();
        assertThat(rule).isEqualTo(SessionRule.defaults());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\n"})
    @DisplayName("빈 값은 기본 규칙")
    void fallsBackOnBlank(String guidance) {
        assertThat(parser.parse(guidance)).isEqualTo(SessionRule.defaults());
    }

    @Test
    @DisplayName("기본 규칙은 수~금 19:30, 토 14:00·18:00, 일 15:00")
    void defaultRule() {
        assertThat(SessionRule.defaults().slots())
                .containsExactly(
                        new Slot(WEDNESDAY, LocalTime.of(19, 30)),
                        new Slot(THURSDAY, LocalTime.of(19, 30)),
                        new Slot(FRIDAY, LocalTime.of(19, 30)),
                        new Slot(SATURDAY, LocalTime.of(14, 0)),
                        new Slot(SATURDAY, LocalTime.of(18, 0)),
                        new Slot(SUNDAY, LocalTime.of(15, 0)));
    }
}
