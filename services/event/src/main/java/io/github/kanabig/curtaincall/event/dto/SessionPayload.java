package io.github.kanabig.curtaincall.event.dto;

import io.github.kanabig.curtaincall.event.entity.SaleSession;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * SessionCreated·SessionUpdated payload. 부분 변경이 아니라 바뀐 뒤 전체 값이고 좌석 목록은 없다. 시각은 오프셋을 포함한 ISO-8601 문자열이다(소비자가
 * {@code OffsetDateTime.parse}로 읽는다).
 */
public record SessionPayload(
        long sessionId,
        long performanceId,
        String venueId,
        String startAt,
        String saleOpenAt,
        List<PriceItem> prices,
        int admissionPerSecond,
        int maxActiveUsers) {

    public record PriceItem(String grade, int price) {}

    public static SessionPayload of(SaleSession s, ZoneId zone) {
        return new SessionPayload(
                s.id(),
                s.performanceId(),
                s.venueId(),
                format(s.startAt(), zone),
                format(s.saleOpenAt(), zone),
                s.prices().stream()
                        .map(p -> new PriceItem(p.grade(), p.price()))
                        .toList(),
                s.admissionPerSecond(),
                s.maxActiveUsers());
    }

    private static String format(java.time.Instant instant, ZoneId zone) {
        return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(OffsetDateTime.ofInstant(instant, zone));
    }
}
