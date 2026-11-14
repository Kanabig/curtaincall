package io.github.kanabig.curtaincall.event.dto;

import java.time.Instant;

/** 회차 판매 상태. 중지 여부와 시각으로 계산하고 저장하지 않는다. 공연이 시작하면 판매가 닫힌다. */
public enum SaleState {
    SCHEDULED,
    ON_SALE,
    SUSPENDED,
    CLOSED;

    public static SaleState of(boolean suspended, Instant saleOpenAt, Instant startAt, Instant now) {
        if (suspended) {
            return SUSPENDED;
        }
        if (!now.isBefore(startAt)) {
            return CLOSED;
        }
        return now.isBefore(saleOpenAt) ? SCHEDULED : ON_SALE;
    }
}
