package io.github.kanabig.curtaincall.event.entity;

import java.time.LocalDate;

/** 실제 KOPIS에서 목록을 끝까지 받은 마지막 실행. date는 시작일(증분 기준일), syncedUntil은 그 실행이 받은 기간의 끝날이다. */
public record LastSync(LocalDate date, LocalDate syncedUntil) {}
