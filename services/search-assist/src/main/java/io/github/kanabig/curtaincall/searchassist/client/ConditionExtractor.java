package io.github.kanabig.curtaincall.searchassist.client;

import java.time.LocalDate;

/** 자연어를 검색 조건 후보로 바꾼다. 결과는 신뢰하지 않고 서비스가 항상 검증한다. */
public interface ConditionExtractor {

    /** @param today 상대 날짜("내일", "이번 주말")의 기준일(KST) */
    RawConditions extract(String query, LocalDate today);
}
