package io.github.kanabig.curtaincall.searchassist.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** SRC-3 */
class MockConditionExtractorTest {

    /** 2026-11-16은 월요일 */
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 16);

    private final MockConditionExtractor extractor = new MockConditionExtractor();

    @Test
    @DisplayName("SRC-3: 지역·장르·가격·날짜를 규칙으로 뽑고 나머지가 keyword가 된다")
    void extractsFields() {
        RawConditions c = extractor.extract("이번 주말 서울에서 5만원 이하 뮤지컬 보고 싶어", TODAY);

        assertThat(c.region()).isEqualTo("서울특별시");
        assertThat(c.genre()).isEqualTo("뮤지컬");
        assertThat(c.maxPrice()).isEqualTo(50_000L);
        assertThat(c.dateFrom()).isEqualTo("2026-11-21");
        assertThat(c.dateTo()).isEqualTo("2026-11-22");
        assertThat(c.keyword()).isNull();
    }

    @Test
    @DisplayName("공연명은 keyword로 남는다")
    void keywordRemains() {
        RawConditions c = extractor.extract("부산 오페라의 유령", TODAY);

        assertThat(c.region()).isEqualTo("부산광역시");
        assertThat(c.keyword()).isEqualTo("오페라의 유령");
    }

    @Test
    @DisplayName("가격 표현: 미만은 1원 적게, 대는 그 구간의 끝")
    void priceVariants() {
        assertThat(extractor.extract("10만 원 미만", TODAY).maxPrice()).isEqualTo(99_999L);
        assertThat(extractor.extract("3만원대 연극", TODAY).maxPrice()).isEqualTo(39_999L);
    }

    @Test
    @DisplayName("날짜 표현: 내일, 다음 주, 12월, 12월 25일, ISO")
    void dateVariants() {
        assertThat(extractor.extract("내일 콘서트", TODAY).dateFrom()).isEqualTo("2026-11-17");
        assertThat(extractor.extract("다음 주", TODAY).dateTo()).isEqualTo("2026-11-29");
        RawConditions december = extractor.extract("12월 클래식", TODAY);
        assertThat(december.dateFrom()).isEqualTo("2026-12-01");
        assertThat(december.dateTo()).isEqualTo("2026-12-31");
        assertThat(december.genre()).isEqualTo("서양음악(클래식)");
        assertThat(extractor.extract("12월 25일", TODAY).dateFrom()).isEqualTo("2026-12-25");
        assertThat(extractor.extract("2027-01-05 공연", TODAY).dateTo()).isEqualTo("2027-01-05");
    }

    @Test
    @DisplayName("이미 지난 달은 내년으로 본다")
    void pastMonthIsNextYear() {
        assertThat(extractor.extract("3월 뮤지컬", TODAY).dateFrom()).isEqualTo("2027-03-01");
    }

    @Test
    @DisplayName("광주·전남은 KOPIS 값 전남광주통합특별시로 매핑된다")
    void gwangju() {
        assertThat(extractor.extract("광주 연극", TODAY).region()).isEqualTo("전남광주통합특별시");
        assertThat(extractor.extract("전남 연극", TODAY).region()).isEqualTo("전남광주통합특별시");
    }

    @Test
    @DisplayName("구·동 단위 지역은 region이 아니라 keyword로 간다")
    void districtGoesToKeyword() {
        RawConditions c = extractor.extract("대학로 연극", TODAY);

        assertThat(c.region()).isNull();
        assertThat(c.genre()).isEqualTo("연극");
        assertThat(c.keyword()).isEqualTo("대학로");
    }
}
