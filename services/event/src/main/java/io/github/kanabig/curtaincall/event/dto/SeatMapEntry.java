package io.github.kanabig.curtaincall.event.dto;

/** 회차 좌석 배치도의 좌석 한 건. seatId는 공연장 배치 템플릿의 좌석 ID다. 판매 상태는 담지 않는다. */
public record SeatMapEntry(
        long seatId,
        int floor,
        String section,
        String row,
        int number,
        int x,
        int y,
        String grade,
        int price,
        boolean wheelchair) {}
