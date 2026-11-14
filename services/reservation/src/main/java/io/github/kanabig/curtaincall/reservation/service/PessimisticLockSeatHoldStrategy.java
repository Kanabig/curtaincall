package io.github.kanabig.curtaincall.reservation.service;

import io.github.kanabig.curtaincall.common.web.error.BusinessException;
import io.github.kanabig.curtaincall.common.web.error.CommonErrorCode;
import io.github.kanabig.curtaincall.reservation.config.HoldProperties;
import io.github.kanabig.curtaincall.reservation.entity.SeatHold;
import io.github.kanabig.curtaincall.reservation.error.ReservationErrorCode;
import io.github.kanabig.curtaincall.reservation.repository.SeatHoldRepository;
import io.github.kanabig.curtaincall.reservation.repository.SeatHoldRepository.LockedSeat;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 좌석 행을 {@code SELECT … FOR UPDATE}로 먼저 잠그고 상태를 확인한 뒤 갱신한다. 비교 측정용 구현이다. 한도 행을 먼저 잠그고 좌석은 {@code seat_id} 오름차순으로
 * 잠가, 서로 다른 순서로 요청이 겹쳐도 교착하지 않게 한다.
 */
@Component
@ConditionalOnProperty(prefix = "curtaincall.hold", name = "strategy", havingValue = "pessimistic-lock")
public class PessimisticLockSeatHoldStrategy implements SeatHoldStrategy {

    private final SeatHoldRepository repository;
    private final HoldProperties properties;
    private final TransactionTemplate tx;
    private final Clock clock = Clock.systemUTC();

    public PessimisticLockSeatHoldStrategy(
            SeatHoldRepository repository, HoldProperties properties, TransactionTemplate tx) {
        this.repository = repository;
        this.properties = properties;
        this.tx = tx;
    }

    @Override
    public SeatHold hold(long sessionId, long userId, List<Long> seatIds) {
        repository.ensureQuotaRow(userId, sessionId);
        return tx.execute(status -> doHold(sessionId, userId, seatIds));
    }

    private SeatHold doHold(long sessionId, long userId, List<Long> seatIds) {
        // 이 트랜잭션의 첫 문장: 같은 사용자의 요청을 직렬화하고, 이후 읽기가 락 뒤의 상태를 보게 한다
        repository.lockQuotaRow(userId, sessionId);
        Instant now = clock.instant();
        int owned = repository.countOwned(sessionId, userId, now);
        if (owned + seatIds.size() > properties.purchaseLimit()) {
            throw new BusinessException(
                    ReservationErrorCode.PURCHASE_LIMIT_EXCEEDED,
                    "회차당 최대 %d매, 이미 %d매 보유".formatted(properties.purchaseLimit(), owned));
        }
        List<LockedSeat> locked = repository.lockSeats(sessionId, seatIds);
        if (locked.size() != seatIds.size()) {
            List<Long> found = locked.stream().map(LockedSeat::seatId).toList();
            List<Long> missing =
                    seatIds.stream().filter(id -> !found.contains(id)).toList();
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "회차에 없는 좌석").with("seatIds", missing);
        }
        List<Long> unavailable = locked.stream()
                .filter(seat -> !seat.holdable(now))
                .map(LockedSeat::seatId)
                .toList();
        if (!unavailable.isEmpty()) {
            throw new BusinessException(
                            ReservationErrorCode.SEAT_ALREADY_HELD,
                            "좌석 %d개 중 %d개를 다른 사용자가 점유 중".formatted(seatIds.size(), unavailable.size()))
                    .with("seatIds", unavailable);
        }
        String holdId = UUID.randomUUID().toString();
        Instant expiresAt = now.plus(properties.ttl());
        int updated = repository.holdSeats(sessionId, userId, holdId, seatIds, now, expiresAt);
        if (updated != seatIds.size()) {
            // 행을 잠근 뒤라 일어나지 않는다. 일어나면 잠금 순서나 조건이 어긋난 것이므로 조용히 넘기지 않는다
            throw new IllegalStateException("잠근 좌석의 갱신 행 수 불일치: %d/%d".formatted(updated, seatIds.size()));
        }
        return new SeatHold(holdId, seatIds, expiresAt);
    }
}
