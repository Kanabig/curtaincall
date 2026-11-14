package io.github.kanabig.curtaincall.event.service;

import io.github.kanabig.curtaincall.common.web.error.BusinessException;
import io.github.kanabig.curtaincall.common.web.error.CommonErrorCode;
import io.github.kanabig.curtaincall.event.config.EventTopicsConfig;
import io.github.kanabig.curtaincall.event.config.KopisProperties;
import io.github.kanabig.curtaincall.event.dto.SessionPayload;
import io.github.kanabig.curtaincall.event.dto.SessionRequests.CreateRequest;
import io.github.kanabig.curtaincall.event.dto.SessionRequests.PriceRequest;
import io.github.kanabig.curtaincall.event.dto.SessionRequests.UpdateRequest;
import io.github.kanabig.curtaincall.event.entity.SaleSession;
import io.github.kanabig.curtaincall.event.entity.SaleSession.Price;
import io.github.kanabig.curtaincall.event.error.EventErrorCode;
import io.github.kanabig.curtaincall.event.repository.SalePerformanceRepository;
import io.github.kanabig.curtaincall.event.repository.SessionRepository;
import io.github.kanabig.curtaincall.outbox.relay.OutboxWriter;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ADM-2, ADM-3. 회차 등록·수정·삭제·중지. 변경과 이벤트(Outbox)는 같은 트랜잭션이다. 오픈 시각 판단은 회차 행을 잠근 뒤 하므로, 삭제와 오픈이 겹쳐도 한쪽만
 * 이긴다.
 */
@Service
public class SessionAdminService {

    /** 오픈 이 시간 전부터는 입장 속도·활성 상한만 바꾼다. 오픈 시각 변경이 이벤트 지연으로 queue에 늦게 닿는 경우를 막는다. */
    public static final Duration EDIT_LOCK_BEFORE_OPEN = Duration.ofMinutes(10);

    private static final String CREATED = "SessionCreated";
    private static final String UPDATED = "SessionUpdated";
    private static final String SUSPENDED = "SessionSuspended";
    private static final String DELETED = "SessionDeleted";

    private final SessionRepository sessions;
    private final SalePerformanceRepository performances;
    private final OutboxWriter outbox;
    private final KopisProperties properties;
    private final Clock clock;

    public SessionAdminService(
            SessionRepository sessions,
            SalePerformanceRepository performances,
            OutboxWriter outbox,
            KopisProperties properties,
            Clock clock) {
        this.sessions = sessions;
        this.performances = performances;
        this.outbox = outbox;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public SaleSession create(long performanceId, CreateRequest request) {
        String venueId = performances
                .findHallId(performanceId)
                .orElseThrow(() -> new BusinessException(EventErrorCode.PERFORMANCE_NOT_FOUND));
        Instant now = clock.instant();
        Instant startAt = request.startAt().toInstant();
        Instant saleOpenAt = request.saleOpenAt().toInstant();
        if (!saleOpenAt.isAfter(now)) {
            throw invalid("판매 오픈 시각은 현재보다 뒤여야 한다");
        }
        if (!startAt.isAfter(saleOpenAt)) {
            throw invalid("공연 시작은 판매 오픈보다 뒤여야 한다");
        }
        List<Price> prices = toPrices(request.prices());
        long id = sessions.insert(
                performanceId,
                startAt,
                saleOpenAt,
                prices,
                request.admissionPerSecond(),
                request.maxActiveUsers(),
                now);
        SaleSession created = sessions.find(id).orElseThrow();
        outbox.append(EventTopicsConfig.SESSION_TOPIC, String.valueOf(id), CREATED, payload(created));
        return created;
    }

    @Transactional
    public SaleSession update(long id, UpdateRequest request) {
        SaleSession current = lock(id);
        Instant now = clock.instant();
        boolean scheduleChange = request.startAt() != null || request.saleOpenAt() != null || request.prices() != null;
        if (!scheduleChange && request.admissionPerSecond() == null && request.maxActiveUsers() == null) {
            throw invalid("바꿀 값이 없다");
        }
        if (scheduleChange && locked(current, now)) {
            throw new BusinessException(EventErrorCode.SESSION_EDIT_LOCKED);
        }
        Instant startAt = request.startAt() == null
                ? current.startAt()
                : request.startAt().toInstant();
        Instant saleOpenAt = request.saleOpenAt() == null
                ? current.saleOpenAt()
                : request.saleOpenAt().toInstant();
        if (request.saleOpenAt() != null && saleOpenAt.isBefore(now.plus(EDIT_LOCK_BEFORE_OPEN))) {
            // 이미 잠금 구간 안으로 옮기면 제한을 피할 수 있다
            throw invalid("판매 오픈 시각은 지금부터 10분 뒤 이후여야 한다");
        }
        if (!startAt.isAfter(saleOpenAt)) {
            throw invalid("공연 시작은 판매 오픈보다 뒤여야 한다");
        }
        List<Price> prices = request.prices() == null ? null : toPrices(request.prices());
        SaleSession next = new SaleSession(
                id,
                current.performanceId(),
                current.venueId(),
                startAt,
                saleOpenAt,
                prices == null ? current.prices() : prices,
                request.admissionPerSecond() == null ? current.admissionPerSecond() : request.admissionPerSecond(),
                request.maxActiveUsers() == null ? current.maxActiveUsers() : request.maxActiveUsers(),
                current.suspended());
        sessions.update(next, prices, now);
        SaleSession saved = sessions.find(id).orElseThrow();
        outbox.append(EventTopicsConfig.SESSION_TOPIC, String.valueOf(id), UPDATED, payload(saved));
        return saved;
    }

    @Transactional
    public void delete(long id) {
        SaleSession current = lock(id);
        if (!clock.instant().isBefore(current.saleOpenAt())) {
            throw new BusinessException(EventErrorCode.SESSION_ALREADY_OPENED);
        }
        sessions.delete(id);
        outbox.append(EventTopicsConfig.SESSION_TOPIC, String.valueOf(id), DELETED, Map.of("sessionId", id));
    }

    /** 이미 중지한 회차는 다시 중지해도 이벤트를 또 내지 않는다. */
    @Transactional
    public void suspend(long id) {
        SaleSession current = lock(id);
        if (current.suspended()) {
            return;
        }
        sessions.suspend(id, clock.instant());
        outbox.append(EventTopicsConfig.SESSION_TOPIC, String.valueOf(id), SUSPENDED, Map.of("sessionId", id));
    }

    private SaleSession lock(long id) {
        return sessions.findForUpdate(id).orElseThrow(() -> new BusinessException(EventErrorCode.SESSION_NOT_FOUND));
    }

    /** 오픈 10분 전 시각 이후(경계 포함)면 잠긴다. */
    private static boolean locked(SaleSession session, Instant now) {
        return !now.isBefore(session.saleOpenAt().minus(EDIT_LOCK_BEFORE_OPEN));
    }

    private SessionPayload payload(SaleSession session) {
        return SessionPayload.of(session, properties.zone());
    }

    private static List<Price> toPrices(List<PriceRequest> requests) {
        Set<String> grades = new HashSet<>();
        for (PriceRequest r : requests) {
            if (!grades.add(r.grade())) {
                throw invalid("등급이 중복되었다: " + r.grade());
            }
        }
        return requests.stream().map(r -> new Price(r.grade(), r.price())).toList();
    }

    private static BusinessException invalid(String detail) {
        return new BusinessException(CommonErrorCode.INVALID_REQUEST, detail);
    }
}
