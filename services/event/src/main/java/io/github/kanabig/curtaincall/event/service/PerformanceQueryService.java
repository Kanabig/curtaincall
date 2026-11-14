package io.github.kanabig.curtaincall.event.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kanabig.curtaincall.common.web.error.BusinessException;
import io.github.kanabig.curtaincall.common.web.error.CommonErrorCode;
import io.github.kanabig.curtaincall.event.config.KopisProperties;
import io.github.kanabig.curtaincall.event.dto.PerformanceQueryDtos.Filter;
import io.github.kanabig.curtaincall.event.dto.PerformanceQueryDtos.PerformanceDetail;
import io.github.kanabig.curtaincall.event.dto.PerformanceQueryDtos.PerformancePage;
import io.github.kanabig.curtaincall.event.dto.PerformanceQueryDtos.PriceItem;
import io.github.kanabig.curtaincall.event.dto.PerformanceQueryDtos.SessionDetail;
import io.github.kanabig.curtaincall.event.dto.PerformanceQueryDtos.SessionItem;
import io.github.kanabig.curtaincall.event.dto.PerformanceState;
import io.github.kanabig.curtaincall.event.dto.SaleState;
import io.github.kanabig.curtaincall.event.entity.SaleSession;
import io.github.kanabig.curtaincall.event.error.EventErrorCode;
import io.github.kanabig.curtaincall.event.repository.PerformanceQueryRepository;
import io.github.kanabig.curtaincall.event.repository.SessionRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.springframework.stereotype.Service;

/** [EVT-4, EVT-5] 공연·회차 조회. 읽기 전용이고 KOPIS 상태와 무관하게 DB의 마지막 동기화 결과를 돌려준다. */
@Service
public class PerformanceQueryService {

    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;
    private static final int MAX_KEYWORD = 100;

    private final PerformanceQueryRepository performances;
    private final SessionRepository sessions;
    private final KopisProperties properties;
    private final Clock clock;
    private final ObjectMapper mapper;

    public PerformanceQueryService(
            PerformanceQueryRepository performances,
            SessionRepository sessions,
            KopisProperties properties,
            Clock clock,
            ObjectMapper mapper) {
        this.performances = performances;
        this.sessions = sessions;
        this.properties = properties;
        this.clock = clock;
        this.mapper = mapper;
    }

    public PerformancePage list(Filter filter, int page, int size) {
        if (page < 0 || size < 1 || size > MAX_SIZE) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "page는 0 이상, size는 1~%d".formatted(MAX_SIZE));
        }
        if (filter.from() != null && filter.to() != null && filter.from().isAfter(filter.to())) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "from이 to보다 늦다");
        }
        if (filter.maxPrice() != null && filter.maxPrice() < 0) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "maxPrice는 0 이상");
        }
        Filter f = normalize(filter);
        Instant now = clock.instant();
        return new PerformancePage(performances.list(f, page, size, now), page, size, performances.count(f, now));
    }

    private static Filter normalize(Filter f) {
        String keyword = blankToNull(f.keyword());
        if (keyword != null && keyword.length() > MAX_KEYWORD) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "keyword는 %d자 이하".formatted(MAX_KEYWORD));
        }
        return new Filter(
                blankToNull(f.region()), blankToNull(f.genre()), f.from(), f.to(), f.state(), f.maxPrice(), keyword);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    public PerformanceDetail get(long id) {
        var row = performances.find(id).orElseThrow(() -> new BusinessException(EventErrorCode.PERFORMANCE_NOT_FOUND));
        Instant now = clock.instant();
        List<SessionItem> items = sessions.findSellable(id, now).stream()
                .map(s -> new SessionItem(
                        s.id(),
                        format(s.startAt()),
                        format(s.saleOpenAt()),
                        SaleState.of(s.suspended(), s.saleOpenAt(), s.startAt(), now),
                        prices(s)))
                .toList();
        return new PerformanceDetail(
                row.id(),
                row.kopisId(),
                row.name(),
                row.genre(),
                row.area(),
                PerformanceState.fromKopis(row.state()),
                row.startDate(),
                row.endDate(),
                row.openRun(),
                row.venueId(),
                row.facilityName(),
                row.poster(),
                row.runtime(),
                row.age(),
                row.priceGuidance(),
                row.dateGuidance(),
                parse(row.relates()),
                items);
    }

    public SessionDetail getSession(long id) {
        SaleSession s = sessions.find(id).orElseThrow(() -> new BusinessException(EventErrorCode.SESSION_NOT_FOUND));
        return new SessionDetail(
                s.id(),
                s.performanceId(),
                format(s.startAt()),
                format(s.saleOpenAt()),
                SaleState.of(s.suspended(), s.saleOpenAt(), s.startAt(), clock.instant()),
                prices(s));
    }

    private static List<PriceItem> prices(SaleSession s) {
        return s.prices().stream().map(p -> new PriceItem(p.grade(), p.price())).toList();
    }

    private String format(Instant instant) {
        return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(OffsetDateTime.ofInstant(instant, properties.zone()));
    }

    private JsonNode parse(String json) {
        if (json == null) {
            return null;
        }
        try {
            return mapper.readTree(json);
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
