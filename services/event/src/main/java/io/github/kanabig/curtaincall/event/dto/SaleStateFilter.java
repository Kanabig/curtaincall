package io.github.kanabig.curtaincall.event.dto;

/** [EVT-4] 공연의 판매 상태 조건. 회차 중 하나라도 판매 중이면 ON_SALE, 아니면서 판매 예정 회차가 있으면 SCHEDULED다. */
public enum SaleStateFilter {
    ON_SALE,
    SCHEDULED
}
