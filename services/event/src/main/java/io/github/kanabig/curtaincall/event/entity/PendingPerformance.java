package io.github.kanabig.curtaincall.event.entity;

/** 상세 조회 대기 공연. */
public record PendingPerformance(String mt20id, int attempts) {}
