package io.github.kanabig.curtaincall.event.service;

import io.github.kanabig.curtaincall.common.web.error.BusinessException;
import io.github.kanabig.curtaincall.common.web.error.CommonErrorCode;
import io.github.kanabig.curtaincall.event.dto.PerformanceState;
import io.github.kanabig.curtaincall.event.dto.RegistrationCandidates.Candidate;
import io.github.kanabig.curtaincall.event.dto.RegistrationCandidates.CandidatePage;
import io.github.kanabig.curtaincall.event.error.EventErrorCode;
import io.github.kanabig.curtaincall.event.repository.HallSeatRepository.HallScale;
import io.github.kanabig.curtaincall.event.repository.PerformanceRepository;
import io.github.kanabig.curtaincall.event.repository.PerformanceRepository.CandidateRow;
import io.github.kanabig.curtaincall.event.repository.SalePerformanceRepository;
import java.time.Clock;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** ADM-1. 동기화된 공연을 판매 대상으로 등록한다. KOPIS를 호출하지 않는다. 같은 공연을 다시 등록하면 처음 ID를 돌려준다. */
@Service
public class PerformanceAdminService {

    private final PerformanceRepository performances;
    private final SalePerformanceRepository salePerformances;
    private final HallLayoutService layouts;
    private final Clock clock;

    public PerformanceAdminService(
            PerformanceRepository performances,
            SalePerformanceRepository salePerformances,
            HallLayoutService layouts,
            Clock clock) {
        this.performances = performances;
        this.salePerformances = salePerformances;
        this.layouts = layouts;
        this.clock = clock;
    }

    @Transactional
    public Registered register(String kopisId) {
        PerformanceRepository.Registrable p = performances
                .findRegistrable(kopisId)
                .orElseThrow(() -> new BusinessException(EventErrorCode.PERFORMANCE_NOT_FOUND));
        if (!p.detailDone()) {
            throw new BusinessException(EventErrorCode.PERFORMANCE_NOT_SYNCED);
        }
        if (p.mt13id() == null) {
            throw new BusinessException(EventErrorCode.HALL_UNKNOWN);
        }
        HallLayoutService.Result layout = layouts.ensureLayout(p.mt13id());
        if (!layout.available()) {
            throw new BusinessException(unsellable(layout.outcome()));
        }
        boolean created = salePerformances.insertIfAbsent(kopisId, clock.instant());
        long id = salePerformances.findId(kopisId).orElseThrow();
        return new Registered(id, kopisId, p.name(), p.mt13id(), created);
    }

    /** 등록 후보를 키워드·페이지로 조회한다. DB만 읽고 배치는 만들지 않는다. 판정은 {@link #register}와 같은 기준이다. */
    @Transactional(readOnly = true)
    public CandidatePage candidates(String keyword, int page, int size) {
        if (page < 0 || size < 1 || size > PerformanceQueryService.MAX_SIZE) {
            throw new BusinessException(
                    CommonErrorCode.INVALID_REQUEST,
                    "page는 0 이상, size는 1~%d".formatted(PerformanceQueryService.MAX_SIZE));
        }
        String k = keyword == null || keyword.isBlank() ? null : keyword.strip();
        if (k != null && k.length() > 100) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "keyword는 100자 이하");
        }
        List<Candidate> content = performances.findCandidates(k, page, size).stream()
                .map(PerformanceAdminService::toCandidate)
                .toList();
        return new CandidatePage(content, page, size, performances.countCandidates(k));
    }

    private static Candidate toCandidate(CandidateRow r) {
        EventErrorCode reason = blockReason(r);
        return new Candidate(
                r.kopisId(),
                r.name(),
                r.facilityName(),
                r.genre(),
                PerformanceState.fromKopis(r.state()),
                r.startDate(),
                r.endDate(),
                r.salePerformanceId() != null,
                r.salePerformanceId(),
                reason == null,
                reason == null ? null : reason.code());
    }

    private static EventErrorCode blockReason(CandidateRow r) {
        if (!r.detailDone()) {
            return EventErrorCode.PERFORMANCE_NOT_SYNCED;
        }
        if (r.mt13id() == null) {
            return EventErrorCode.HALL_UNKNOWN;
        }
        HallScale scale = r.hallKnown() ? new HallScale(r.seatScale(), null) : null;
        HallLayoutService.Outcome outcome = HallLayoutService.judge(r.hasLayout(), scale);
        return switch (outcome) {
            case CREATED, EXISTING -> null;
            default -> unsellable(outcome);
        };
    }

    private static EventErrorCode unsellable(HallLayoutService.Outcome outcome) {
        return switch (outcome) {
            case UNKNOWN_HALL -> EventErrorCode.HALL_UNKNOWN;
            case NO_SEAT_SCALE -> EventErrorCode.HALL_NO_SEAT_SCALE;
            case SEAT_SCALE_TOO_LARGE -> EventErrorCode.HALL_SEAT_SCALE_TOO_LARGE;
            case CREATED, EXISTING -> throw new IllegalArgumentException("판매할 수 있는 결과: " + outcome);
        };
    }

    public record Registered(long id, String kopisId, String name, String venueId, boolean created) {}
}
