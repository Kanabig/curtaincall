package io.github.kanabig.curtaincall.event.dto;

/** [EVT-4] 목록 정렬. 같은 값이면 판매 등록 순서로 고정한다. */
public enum PerformanceSort {
    START("p.start_date, sp.id"),
    END("p.end_date, sp.id"),
    RECENT("sp.id DESC");

    private final String orderBy;

    PerformanceSort(String orderBy) {
        this.orderBy = orderBy;
    }

    public String orderBy() {
        return orderBy;
    }
}
