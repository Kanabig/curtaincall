package io.github.kanabig.curtaincall.event.entity;

import java.time.LocalDate;

/** 동기화 실행 결과 요약. */
public record SyncRun(
        long id,
        SyncStatus status,
        LocalDate afterDate,
        LocalDate syncedUntil,
        boolean listCompleted,
        int calls,
        int listed,
        int detailsFetched,
        int facilitiesFetched,
        int parseFailures,
        String error) {}
