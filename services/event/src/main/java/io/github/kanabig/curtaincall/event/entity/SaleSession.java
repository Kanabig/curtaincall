package io.github.kanabig.curtaincall.event.entity;

import java.time.Instant;
import java.util.List;

/** 판매 회차. 이벤트에 싣는 값은 모두 여기 있다. */
public record SaleSession(
        long id,
        long performanceId,
        String venueId,
        Instant startAt,
        Instant saleOpenAt,
        List<Price> prices,
        int admissionPerSecond,
        int maxActiveUsers,
        boolean suspended) {

    public record Price(String grade, int price) {}
}
