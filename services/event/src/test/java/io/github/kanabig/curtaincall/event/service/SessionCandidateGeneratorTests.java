package io.github.kanabig.curtaincall.event.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.kanabig.curtaincall.event.dto.SessionCandidate;
import io.github.kanabig.curtaincall.event.dto.SessionRule;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** EVT-8: 규칙으로 회차 후보와 판매 오픈 시각을 만든다. */
class SessionCandidateGeneratorTests {

    private final SessionCandidateGenerator generator = new SessionCandidateGenerator();
    private final DateGuidanceParser parser = new DateGuidanceParser();

    @Test
    @DisplayName("기간 안의 해당 요일마다 후보를 만들고 오픈 시각은 14일 전 14:00")
    void generatesWithinPeriod() {
        SessionRule rule = parser.parse("수요일(10:30), 토요일(14:00)");

        // 2026-11-14는 토요일
        List<SessionCandidate> list = generator.generate(
                rule, LocalDate.of(2026, 11, 14), LocalDate.of(2026, 12, 19), LocalDate.of(2026, 11, 14), 100);

        assertThat(list).hasSize(11);
        assertThat(list.get(0).startAt()).isEqualTo(LocalDateTime.of(2026, 11, 14, 14, 0));
        assertThat(list.get(0).saleOpenAt()).isEqualTo(LocalDateTime.of(2026, 10, 31, 14, 0));
        assertThat(list.get(1).startAt()).isEqualTo(LocalDateTime.of(2026, 11, 18, 10, 30));
        assertThat(list.get(10).startAt()).isEqualTo(LocalDateTime.of(2026, 12, 19, 14, 0));
        assertThat(list).extracting(SessionCandidate::startAt).isSorted();
    }

    @Test
    @DisplayName("from 이전은 만들지 않고 상한만큼만 만든다")
    void respectsFromAndLimit() {
        List<SessionCandidate> list = generator.generate(
                SessionRule.defaults(),
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2027, 12, 31),
                LocalDate.of(2026, 11, 14),
                7);

        assertThat(list).hasSize(7);
        assertThat(list.get(0).startAt().toLocalDate()).isAfterOrEqualTo(LocalDate.of(2026, 11, 14));
    }

    @Test
    @DisplayName("같은 날 회차는 오픈 시각이 같다. 기간이 비면 후보가 없다")
    void sameDaySharesSaleOpen() {
        List<SessionCandidate> list = generator.generate(
                SessionRule.defaults(),
                LocalDate.of(2026, 11, 14),
                LocalDate.of(2026, 11, 14),
                LocalDate.of(2026, 11, 14),
                10);

        assertThat(list).hasSize(2);
        assertThat(list.get(0).saleOpenAt()).isEqualTo(list.get(1).saleOpenAt());
        assertThat(generator.generate(
                        SessionRule.defaults(),
                        LocalDate.of(2026, 11, 1),
                        LocalDate.of(2026, 11, 10),
                        LocalDate.of(2026, 11, 14),
                        10))
                .isEmpty();
    }
}
