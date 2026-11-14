package io.github.kanabig.curtaincall.reservation.entity;

/** 회차 재고로 만들 좌석. 점유 전이라 상태와 점유자 정보는 없다. */
public record NewSeat(long seatId, String grade, int price) {}
