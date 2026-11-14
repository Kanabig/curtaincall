package io.github.kanabig.curtaincall.reservation.service;

import io.github.kanabig.curtaincall.common.web.error.BusinessException;
import io.github.kanabig.curtaincall.common.web.error.CommonErrorCode;
import io.github.kanabig.curtaincall.reservation.config.HoldProperties;
import io.github.kanabig.curtaincall.reservation.entity.SeatHold;
import io.github.kanabig.curtaincall.reservation.error.ReservationErrorCode;
import io.github.kanabig.curtaincall.reservation.repository.SeatHoldRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 조건부 UPDATE 한 문장으로 좌석을 잡는다. 같은 사용자의 요청은 한도 행의 {@code FOR UPDATE}로 직렬화하고, 다른 사용자끼리는 좌석 행 락만 겹친다. 좌석은 항상 오름차순으로
 * 갱신해 잠금 순서를 맞춘다.
 */
@Component
@ConditionalOnProperty(
        prefix = "curtaincall.hold",
        name = "strategy",
        havingValue = "conditional-update",
        matchIfMissing = true)
public class ConditionalUpdateSeatHoldStrategy implements SeatHoldStrategy {

    private final SeatHoldRepository repository;
    private final HoldProperties properties;
    private final TransactionTemplate tx;
    private final Clock clock = Clock.systemUTC();

    public ConditionalUpdateSeatHoldStrategy(
            SeatHoldRepository repository, HoldProperties properties, TransactionTemplate tx) {
        this.repository = repository;
        this.properties = properties;
        this.tx = tx;
    }

    @Override
    public SeatHold hold(long sessionId, long userId, List<Long> seatIds) {
        repository.ensureQuotaRow(userId, sessionId);
        try {
            return tx.execute(status -> doHold(sessionId, userId, seatIds));
        } catch (SeatConflict e) {
            // 롤백이 끝난 뒤에 어느 좌석이 막았는지 읽는다
            throw conflict(sessionId, seatIds);
        }
    }

    private SeatHold doHold(long sessionId, long userId, List<Long> seatIds) {
        // 이 트랜잭션의 첫 문장이어야 한다. 이후의 일관된 읽기(스냅샷)가 락을 얻은 뒤에 만들어져 같은 사용자의 앞선 점유가 보인다
        repository.lockQuotaRow(userId, sessionId);
        Instant now = clock.instant();
        int owned = repository.countOwned(sessionId, userId, now);
        if (owned + seatIds.size() > properties.purchaseLimit()) {
            throw new BusinessException(
                    ReservationErrorCode.PURCHASE_LIMIT_EXCEEDED,
                    "회차당 최대 %d매, 이미 %d매 보유".formatted(properties.purchaseLimit(), owned));
        }
        String holdId = UUID.randomUUID().toString();
        Instant expiresAt = now.plus(properties.ttl());
        int updated = repository.holdSeats(sessionId, userId, holdId, seatIds, now, expiresAt);
        if (updated != seatIds.size()) {
            throw new SeatConflict();
        }
        return new SeatHold(holdId, seatIds, expiresAt);
    }

    private BusinessException conflict(long sessionId, List<Long> seatIds) {
        List<Long> missing = repository.findMissing(sessionId, seatIds);
        if (!missing.isEmpty()) {
            return new BusinessException(CommonErrorCode.INVALID_REQUEST, "회차에 없는 좌석").with("seatIds", missing);
        }
        List<Long> unavailable = repository.findUnavailable(sessionId, seatIds, clock.instant());
        return new BusinessException(
                        ReservationErrorCode.SEAT_ALREADY_HELD,
                        "좌석 %d개 중 %d개를 다른 사용자가 점유 중".formatted(seatIds.size(), unavailable.size()))
                .with("seatIds", unavailable);
    }

    /** 바뀐 행 수가 모자라 롤백해야 할 때 트랜잭션 콜백에서 던진다. */
    private static final class SeatConflict extends RuntimeException {
        SeatConflict() {
            super(null, null, false, false);
        }
    }
}
