package io.github.kanabig.curtaincall.reservation.entity;

/** 재고 생성을 맡은 회차와 지금까지의 시도 횟수. */
public record PendingStock(long sessionId, int attempts) {}
