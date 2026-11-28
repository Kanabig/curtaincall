package io.github.kanabig.curtaincall.event.entity;

/** 상세를 받을 공연. refetch는 이미 받은 상세에 추가 항목을 채우려고 다시 받는 경우다. */
public record PendingPerformance(String mt20id, int attempts, boolean refetch) {}
