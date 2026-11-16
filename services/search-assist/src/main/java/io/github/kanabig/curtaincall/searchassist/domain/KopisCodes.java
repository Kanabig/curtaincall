package io.github.kanabig.curtaincall.searchassist.domain;

import java.util.List;

/** event 검색이 {@code area}, {@code genrenm}을 정확 일치로 비교하므로 KOPIS가 내려주는 문자열 그대로 둔다. */
public final class KopisCodes {

    public static final List<String> REGIONS = List.of(
            "서울특별시",
            "부산광역시",
            "대구광역시",
            "인천광역시",
            "대전광역시",
            "울산광역시",
            "세종특별자치시",
            "경기도",
            "강원특별자치도",
            "충청북도",
            "충청남도",
            "전북특별자치도",
            "전남광주통합특별시",
            "경상북도",
            "경상남도",
            "제주특별자치도");

    public static final List<String> GENRES =
            List.of("연극", "무용(서양/한국무용)", "대중무용", "서양음악(클래식)", "한국음악(국악)", "대중음악", "복합", "서커스/마술", "뮤지컬");

    private KopisCodes() {}
}
