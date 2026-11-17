package io.github.kanabig.curtaincall.searchassist.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.kanabig.curtaincall.searchassist.client.RawConditions;
import io.github.kanabig.curtaincall.searchassist.dto.SearchConditions;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** SRC-1 */
class ConditionValidatorTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 16);
    private final ConditionValidator validator = new ConditionValidator();

    private static RawConditions raw(String region, String from, String to, String genre, Long price, String keyword) {
        return new RawConditions(region, from, to, genre, price, keyword);
    }

    @Test
    @DisplayName("SRC-1: 허용된 값은 그대로 통과한다")
    void accepts() {
        SearchConditions c =
                validator.validate(raw("서울특별시", "2026-12-01", "2026-12-31", "뮤지컬", 50_000L, "오페라의 유령"), TODAY);

        assertThat(c.region()).isEqualTo("서울특별시");
        assertThat(c.dateFrom()).isEqualTo(LocalDate.of(2026, 12, 1));
        assertThat(c.genre()).isEqualTo("뮤지컬");
        assertThat(c.maxPrice()).isEqualTo(50_000);
        assertThat(c.keyword()).isEqualTo("오페라의 유령");
    }

    @Test
    @DisplayName("SRC-1: 전부 null이어도 통과한다")
    void allNull() {
        assertThat(validator.validate(raw(null, null, null, null, null, null), TODAY))
                .isEqualTo(new SearchConditions(null, null, null, null, null, null));
    }

    @Test
    @DisplayName("SRC-1: 열거형·날짜·가격 범위를 벗어나면 필드 이름과 함께 거절한다")
    void rejectsOutOfRange() {
        assertInvalid(raw("화성", null, null, null, null, null), "region");
        assertInvalid(raw(null, null, null, "오페라", null, null), "genre");
        assertInvalid(raw(null, "내일", null, null, null, null), "dateFrom");
        assertInvalid(raw(null, "2026-11-15", null, null, null, null), "dateFrom");
        assertInvalid(raw(null, null, "2028-01-01", null, null, null), "dateTo");
        assertInvalid(raw(null, "2026-12-10", "2026-12-01", null, null, null), "dateTo");
        assertInvalid(raw(null, null, null, null, 999L, null), "maxPrice");
        assertInvalid(raw(null, null, null, null, 1_000_001L, null), "maxPrice");
        assertInvalid(raw(null, null, null, null, 99_999_999_999L, null), "maxPrice");
        assertInvalid(raw(null, null, null, null, null, "가".repeat(51)), "keyword");
    }

    @Test
    @DisplayName("keyword의 제어문자는 지우고 공백뿐이면 null이다")
    void keywordCleanup() {
        assertThat(validator
                        .validate(raw(null, null, null, null, null, "  햄\u0000릿\n "), TODAY)
                        .keyword())
                .isEqualTo("햄릿");
        assertThat(validator
                        .validate(raw(null, null, null, null, null, " \t "), TODAY)
                        .keyword())
                .isNull();
    }

    @Test
    @DisplayName("fallback용 keyword는 50자로 자른다")
    void cleanKeywordTruncates() {
        assertThat(ConditionValidator.cleanKeyword("가".repeat(80), 50)).hasSize(50);
        assertThat(ConditionValidator.cleanKeyword(null, 50)).isNull();
    }

    private void assertInvalid(RawConditions raw, String field) {
        assertThatThrownBy(() -> validator.validate(raw, TODAY))
                .isInstanceOfSatisfying(
                        InvalidConditionsException.class,
                        e -> assertThat(e.field()).isEqualTo(field));
    }
}
