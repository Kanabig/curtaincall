package io.github.kanabig.curtaincall.reservation.service;

import io.github.kanabig.curtaincall.common.web.error.BusinessException;
import io.github.kanabig.curtaincall.reservation.client.EventClient;
import io.github.kanabig.curtaincall.reservation.client.SeatMapEntry;
import io.github.kanabig.curtaincall.reservation.config.SeatInventoryProperties;
import io.github.kanabig.curtaincall.reservation.entity.NewSeat;
import io.github.kanabig.curtaincall.reservation.entity.PendingStock;
import io.github.kanabig.curtaincall.reservation.error.ReservationErrorCode;
import io.github.kanabig.curtaincall.reservation.repository.SeatInventoryRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * [EVT-9] 회차별 좌석 재고. 원본은 reservation이다.
 *
 * <p>회차 이벤트를 받으면 상태 행(PENDING)만 남기고, event 호출과 재고 INSERT는 {@link #processPending()}이 맡는다. 이벤트 소비 트랜잭션이 HTTP 호출 동안 커넥션을
 * 쥐지 않고, event가 컨슈머 재시도 시간보다 오래 멈춰도 복구 뒤 스스로 수렴한다.
 */
@Service
public class SeatInventoryService {

    private static final Logger log = LoggerFactory.getLogger(SeatInventoryService.class);

    private final EventClient eventClient;
    private final SeatInventoryRepository repository;
    private final SeatInventoryProperties properties;
    private final TransactionTemplate tx;
    private final Clock clock;

    public SeatInventoryService(
            EventClient eventClient,
            SeatInventoryRepository repository,
            SeatInventoryProperties properties,
            TransactionTemplate tx) {
        this.eventClient = eventClient;
        this.repository = repository;
        this.properties = properties;
        this.tx = tx;
        this.clock = Clock.systemUTC();
    }

    /** 재고 생성 대상으로 등록한다. 이벤트 소비 트랜잭션 안에서 부른다. */
    public void requestInventory(long sessionId) {
        requestInventory(sessionId, null);
    }

    /** {@code startAt}은 예매 취소 기한 계산에 쓴다. */
    public void requestInventory(long sessionId, Instant startAt) {
        repository.insertPending(sessionId, clock.instant(), startAt);
    }

    /** 회차 수정 이벤트로 시작 시각을 맞춘다. */
    public void updateStartAt(long sessionId, Instant startAt) {
        repository.updateStartAt(sessionId, startAt);
    }

    /** 회차 수정 이벤트로 가격 반영을 요청한다. event 호출은 {@link #syncPrices()}가 한다. */
    public void requestPriceSync(long sessionId, Instant occurredAt) {
        repository.requestPriceSync(sessionId, occurredAt);
    }

    /**
     * 가격 수정이 남은 회차의 재고 등급·가격을 event의 최신 배치로 맞춘다. 요청마다 최신 배치를 읽으므로 같은 요청을 다시 처리하거나 이벤트가 뒤집혀 와도 결과가 같다. 가격은 오픈 전에만
     * 바뀌어 점유가 없으므로 AVAILABLE이 아닌 행은 건드리지 않고, 그런 행에 바꿀 값이 있으면 경고만 남긴다.
     *
     * @return 이번에 반영을 마친 회차 수
     */
    public int syncPrices() {
        int done = 0;
        for (SeatInventoryRepository.PriceSyncRequest request : repository.findDuePriceSync(properties.maxPerRun())) {
            try {
                syncPrices(request);
                done++;
            } catch (RuntimeException e) {
                log.warn("재고 가격 반영에 실패했다. 다음 주기에 다시 시도한다: sessionId={}", request.sessionId(), e);
            }
        }
        return done;
    }

    private void syncPrices(SeatInventoryRepository.PriceSyncRequest request) {
        long sessionId = request.sessionId();
        List<SeatMapEntry> seatMap = eventClient.fetchSeatMap(sessionId);
        if (seatMap.isEmpty()) {
            throw new IllegalStateException("좌석 배치가 비어 있다");
        }
        Map<Long, SeatInventoryRepository.StoredSeat> stored = new HashMap<>();
        repository.findStoredSeats(sessionId).forEach(seat -> stored.put(seat.seatId(), seat));
        List<NewSeat> changes = new ArrayList<>();
        int lockedChanges = 0;
        for (SeatMapEntry entry : seatMap) {
            SeatInventoryRepository.StoredSeat current = stored.get(entry.seatId());
            if (current == null || (current.grade().equals(entry.grade()) && current.price() == entry.price())) {
                continue;
            }
            if ("AVAILABLE".equals(current.status())) {
                changes.add(new NewSeat(entry.seatId(), entry.grade(), entry.price()));
            } else {
                lockedChanges++;
            }
        }
        int updated = repository.updatePricesIfAvailable(sessionId, changes, properties.insertBatchSize());
        if (lockedChanges > 0) {
            log.warn("점유·판매된 좌석은 가격을 바꾸지 않았다: sessionId={} seats={}", sessionId, lockedChanges);
        }
        repository.markPriceSynced(sessionId, request.requestedAt());
        log.info("회차 재고 가격을 맞췄다: sessionId={} updated={}", sessionId, updated);
    }

    /**
     * 처리할 시각이 된 PENDING 회차를 한 번에 최대 {@code maxPerRun}개 처리한다. 인스턴스가 여럿이어도 한 회차는 하나만 잡는다.
     *
     * @return 이번에 READY가 된 회차 수
     */
    public int processPending() {
        int ready = 0;
        for (int i = 0; i < properties.maxPerRun(); i++) {
            Instant now = clock.instant();
            Optional<PendingStock> claimed =
                    tx.execute(status -> repository.claimNextDue(now, now.plus(properties.lease())));
            if (claimed == null || claimed.isEmpty()) {
                break;
            }
            if (create(claimed.get())) {
                ready++;
            }
        }
        return ready;
    }

    private boolean create(PendingStock pending) {
        long sessionId = pending.sessionId();
        try {
            List<SeatMapEntry> seatMap = eventClient.fetchSeatMap(sessionId);
            if (seatMap.isEmpty()) {
                throw new IllegalStateException("좌석 배치가 비어 있다");
            }
            List<NewSeat> seats = seatMap.stream()
                    .map(entry -> new NewSeat(entry.seatId(), entry.grade(), entry.price()))
                    .toList();
            // 재고 행과 READY 표시를 함께 커밋한다. 중간에 실패하면 둘 다 남지 않는다
            Integer inserted = tx.execute(status -> {
                int count = repository.insertAll(sessionId, seats, properties.insertBatchSize());
                repository.markReady(sessionId, seats.size(), clock.instant());
                return count;
            });
            log.info("회차 재고를 만들었다: sessionId={} seats={} inserted={}", sessionId, seats.size(), inserted);
            return true;
        } catch (RuntimeException e) {
            Duration backoff = backoff(pending.attempts());
            log.warn(
                    "회차 재고 생성에 실패했다. {} 뒤 다시 시도한다: sessionId={} attempts={}",
                    backoff,
                    sessionId,
                    pending.attempts() + 1,
                    e);
            tx.executeWithoutResult(status ->
                    repository.markFailed(sessionId, clock.instant().plus(backoff), String.valueOf(e.getMessage())));
            return false;
        }
    }

    private Duration backoff(int attempts) {
        Duration base = properties.retryBackoff();
        Duration max = properties.maxBackoff();
        // attempts가 커도 오버플로하지 않게 지수를 제한한다
        Duration delay = base.multipliedBy(1L << Math.min(attempts, 20));
        return delay.compareTo(max) > 0 ? max : delay;
    }

    /** 재고가 준비되지 않은 회차는 점유를 받지 않는다. 점유 API가 맨 앞에서 부른다. */
    public void requireReady(long sessionId) {
        if (!repository.isReady(sessionId)) {
            throw new BusinessException(ReservationErrorCode.SESSION_NOT_ON_SALE, "회차 재고가 아직 준비되지 않았다");
        }
    }
}
