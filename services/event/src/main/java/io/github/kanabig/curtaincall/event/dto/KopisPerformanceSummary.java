package io.github.kanabig.curtaincall.event.dto;

import java.time.LocalDate;

/** 공연목록 한 건. 시설 ID는 상세에만 있다. */
public record KopisPerformanceSummary(
        String mt20id,
        String name,
        LocalDate startDate,
        LocalDate endDate,
        String facilityName,
        String poster,
        String area,
        String genre,
        boolean openRun,
        String state) {}
