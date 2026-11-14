package io.github.kanabig.curtaincall.event.service;

import io.github.kanabig.curtaincall.common.web.error.BusinessException;
import io.github.kanabig.curtaincall.event.error.EventErrorCode;
import io.github.kanabig.curtaincall.event.repository.PerformanceRepository;
import io.github.kanabig.curtaincall.event.repository.SalePerformanceRepository;
import java.time.Clock;
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
        if (!p.detailDone() || p.mt13id() == null) {
            throw new BusinessException(EventErrorCode.PERFORMANCE_NOT_SYNCED);
        }
        if (!layouts.ensureLayout(p.mt13id()).available()) {
            throw new BusinessException(EventErrorCode.HALL_LAYOUT_UNAVAILABLE);
        }
        boolean created = salePerformances.insertIfAbsent(kopisId, clock.instant());
        long id = salePerformances.findId(kopisId).orElseThrow();
        return new Registered(id, kopisId, p.name(), p.mt13id(), created);
    }

    public record Registered(long id, String kopisId, String name, String venueId, boolean created) {}
}
