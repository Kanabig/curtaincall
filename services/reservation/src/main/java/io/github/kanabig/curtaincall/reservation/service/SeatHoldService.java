package io.github.kanabig.curtaincall.reservation.service;

import io.github.kanabig.curtaincall.common.web.error.BusinessException;
import io.github.kanabig.curtaincall.common.web.error.CommonErrorCode;
import io.github.kanabig.curtaincall.common.web.metrics.MetricNames;
import io.github.kanabig.curtaincall.reservation.config.HoldProperties;
import io.github.kanabig.curtaincall.reservation.entity.SeatHold;
import io.github.kanabig.curtaincall.reservation.error.ReservationErrorCode;
import io.github.kanabig.curtaincall.reservation.repository.SeatHoldRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Service;

/** [RSV-1~4] 좌석 점유와 해제. 점유 방법은 {@link SeatHoldStrategy}에 맡긴다. */
@Service
public class SeatHoldService {

    private final SeatInventoryService inventoryService;
    private final SeatHoldStrategy strategy;
    private final SeatHoldRepository repository;
    private final HoldProperties properties;
    private final MeterRegistry meterRegistry;

    public SeatHoldService(
            SeatInventoryService inventoryService,
            SeatHoldStrategy strategy,
            SeatHoldRepository repository,
            HoldProperties properties,
            MeterRegistry meterRegistry) {
        this.inventoryService = inventoryService;
        this.strategy = strategy;
        this.repository = repository;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
    }

    public SeatHold hold(long sessionId, long userId, List<Long> requestedSeatIds) {
        // 재고가 준비되지 않은 회차는 가장 먼저 거절한다
        inventoryService.requireReady(sessionId);
        List<Long> seatIds = normalize(requestedSeatIds);

        long start = System.nanoTime();
        String result = "error";
        try {
            SeatHold hold = strategy.hold(sessionId, userId, seatIds);
            result = "success";
            return hold;
        } catch (BusinessException e) {
            result = resultOf(e);
            throw e;
        } finally {
            Timer.builder(MetricNames.SEAT_HOLD)
                    .tag(MetricNames.TAG_RESULT, result)
                    .publishPercentileHistogram()
                    .register(meterRegistry)
                    .record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
        }
    }

    /** 본인 점유만 풀 수 있다. 없거나, 이미 확정·만료되어 넘어갔거나, 남의 점유면 같은 404다. */
    public void release(String holdId, long userId) {
        if (repository.release(holdId, userId) == 0) {
            throw new BusinessException(ReservationErrorCode.HOLD_NOT_FOUND);
        }
    }

    private List<Long> normalize(List<Long> seatIds) {
        List<Long> sorted = seatIds.stream().distinct().sorted().toList();
        if (sorted.size() != seatIds.size()) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "중복된 좌석이 있다");
        }
        if (sorted.isEmpty() || sorted.size() > properties.purchaseLimit()) {
            throw new BusinessException(
                    CommonErrorCode.INVALID_REQUEST, "좌석은 1~%d개를 고를 수 있다".formatted(properties.purchaseLimit()));
        }
        return sorted;
    }

    private static String resultOf(BusinessException e) {
        if (e.errorCode() == ReservationErrorCode.SEAT_ALREADY_HELD) {
            return "conflict";
        }
        if (e.errorCode() == ReservationErrorCode.PURCHASE_LIMIT_EXCEEDED) {
            return "limit_exceeded";
        }
        return "error";
    }
}
