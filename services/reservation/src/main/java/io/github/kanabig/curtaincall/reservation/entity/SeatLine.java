package io.github.kanabig.curtaincall.reservation.entity;

/** 예매 좌석 한 줄: 좌석 ID와 재고에 적힌 등급·가격. */
public record SeatLine(long seatId, String grade, int price) {}
