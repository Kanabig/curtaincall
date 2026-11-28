package io.github.kanabig.curtaincall.event.dto;

import java.time.LocalDate;
import java.util.List;
import tools.jackson.databind.JsonNode;

/** 조회 API 응답. */
public final class PerformanceQueryDtos {

    private PerformanceQueryDtos() {}

    /** 목록 조건. null이거나 빈 항목은 걸지 않는다. 목록 값은 항목 안에서 OR, 항목끼리는 AND다. */
    public record Filter(
            List<String> regions,
            String genre,
            LocalDate from,
            LocalDate to,
            PerformanceState state,
            List<SaleStateFilter> saleStates,
            List<Category> categories,
            Integer maxPrice,
            String keyword,
            PerformanceSort sort) {}

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
            Integer minPrice,
            List<Category> categories) {}

    public record PerformancePage(List<PerformanceItem> content, int page, int size, long totalElements) {}

    public record PriceItem(String grade, int price) {}

    public record SessionItem(
            long id,
            String startAt,
            String saleOpenAt,
            SaleState saleState,
            List<PriceItem> prices,
            int admissionPerSecond,
            int maxActiveUsers) {}

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
            List<String> grades,
            List<SessionItem> sessions,
            String cast,
            String producer,
            List<String> introImages,
            List<Category> categories,
            VenueInfo venue) {}

    /** 공연장 시설. 값이 확인되지 않으면 null이다. */
    public record VenueInfo(
            String phone,
            String website,
            Boolean parkingLot,
            KopisFacility.Accessibility accessibility,
            KopisFacility.Amenities amenities) {}

    public record SessionDetail(
            long id,
            long performanceId,
            String startAt,
            String saleOpenAt,
            SaleState saleState,
            List<PriceItem> prices,
            int admissionPerSecond,
            int maxActiveUsers) {}
}
