package io.github.kanabig.curtaincall.event.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.List;

/** 조회 API 응답. */
public final class PerformanceQueryDtos {

    private PerformanceQueryDtos() {}

    /** 목록 조건. null인 항목은 걸지 않는다. */
    public record Filter(
            String region,
            String genre,
            LocalDate from,
            LocalDate to,
            PerformanceState state,
            Integer maxPrice,
            String keyword) {}

    public record PerformanceItem(
            long id,
            String name,
            String genre,
            String area,
            PerformanceState state,
            LocalDate startDate,
            LocalDate endDate,
            String facilityName,
            String poster,
            Integer minPrice) {}

    public record PerformancePage(List<PerformanceItem> content, int page, int size, long totalElements) {}

    public record PriceItem(String grade, int price) {}

    public record SessionItem(
            long id, String startAt, String saleOpenAt, SaleState saleState, List<PriceItem> prices) {}

    public record PerformanceDetail(
            long id,
            String kopisId,
            String name,
            String genre,
            String area,
            PerformanceState state,
            LocalDate startDate,
            LocalDate endDate,
            boolean openRun,
            String venueId,
            String facilityName,
            String poster,
            String runtime,
            String age,
            String priceGuidance,
            String dateGuidance,
            JsonNode relates,
            List<SessionItem> sessions) {}

    public record SessionDetail(
            long id,
            long performanceId,
            String startAt,
            String saleOpenAt,
            SaleState saleState,
            List<PriceItem> prices) {}
}
