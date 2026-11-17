package io.github.kanabig.curtaincall.searchassist.client;

/** 추출기가 돌려주는 검증 전 값. 날짜는 문자열 그대로 두고 검증기가 해석한다. */
public record RawConditions(
        String region, String dateFrom, String dateTo, String genre, Long maxPrice, String keyword) {}
