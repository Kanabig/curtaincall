package io.github.kanabig.curtaincall.searchassist.dto;

import java.time.LocalDate;

/** event 검색 API(EVT-4)에 그대로 넘길 수 있는 조건. 값이 없는 필드는 null. */
public record SearchConditions(
        String region, LocalDate dateFrom, LocalDate dateTo, String genre, Integer maxPrice, String keyword) {

    public static SearchConditions keywordOnly(String keyword) {
        return new SearchConditions(null, null, null, null, null, keyword);
    }
}
