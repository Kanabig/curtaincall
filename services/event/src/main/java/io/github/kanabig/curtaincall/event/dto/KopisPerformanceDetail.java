package io.github.kanabig.curtaincall.event.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** 공연상세. 가격·시간 안내는 자유 텍스트라 원문 그대로 둔다. */
public record KopisPerformanceDetail(
        String mt20id,
        String name,
        String mt10id,
        String mt13id,
        String facilityName,
        LocalDate startDate,
        LocalDate endDate,
        String genre,
        String area,
        String state,
        boolean openRun,
        String poster,
        String runtime,
        String age,
        String priceGuidance,
        String dateGuidance,
        LocalDateTime updatedAt,
        List<Relate> relates) {

    public record Relate(String name, String url) {}
}
