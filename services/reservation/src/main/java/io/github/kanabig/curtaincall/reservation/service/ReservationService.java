package io.github.kanabig.curtaincall.reservation.service;

import io.github.kanabig.curtaincall.common.web.error.BusinessException;
import io.github.kanabig.curtaincall.reservation.dto.CancelResponse;
import io.github.kanabig.curtaincall.reservation.entity.Order;
import io.github.kanabig.curtaincall.reservation.entity.Reservation;
import io.github.kanabig.curtaincall.reservation.entity.ReservationStatus;
import io.github.kanabig.curtaincall.reservation.error.ReservationErrorCode;
import io.github.kanabig.curtaincall.reservation.repository.OrderRepository;
import io.github.kanabig.curtaincall.reservation.repository.ReservationRepository;
import io.github.kanabig.curtaincall.reservation.repository.SeatInventoryRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * [RSV-9] 예매 취소. 예매를 CANCEL_REQUESTED로 두고 환불을 요청할 뿐 좌석은 풀지 않는다. 좌석 해제와 CANCELLED는 PaymentCancelled를 받는
 * {@link OrderSagaService}가 맡는다.
 *
 * <p>환불 명령은 한 번만 보낸다. 전달은 outbox와 processed_event가, 환불 재시도는 payment가 이어 가므로 여기서 다시 보내지 않는다.
 */
@Service
public class ReservationService {

    static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private static final Logger log = LoggerFactory.getLogger(ReservationService.class);

    private final OrderRepository orders;
    private final ReservationRepository reservations;
    private final SeatInventoryRepository inventory;
    private final OrderEvents events;
    private final IdempotentRequests idempotency;
    private final Clock clock;

    public ReservationService(
            OrderRepository orders,
            ReservationRepository reservations,
            SeatInventoryRepository inventory,
            OrderEvents events,
            IdempotentRequests idempotency,
            Clock clock) {
        this.orders = orders;
        this.reservations = reservations;
        this.inventory = inventory;
        this.events = events;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    public CancelResponse cancel(long userId, String reservationId, String idempotencyKey) {
        return idempotency.execute(
                userId,
                "reservation:cancel:" + reservationId,
                idempotencyKey,
                CancelResponse.class,
                () -> requestCancel(userId, reservationId));
    }

    private CancelResponse requestCancel(long userId, String reservationId) {
        Reservation found = reservations.find(reservationId).orElseThrow(ReservationService::notFound);
        if (found.userId() != userId) {
            throw notFound();
        }
        // 잠금 순서는 Saga와 같다: 주문 → 예매 → 좌석
        Order order = orders.lock(found.orderId()).orElseThrow(ReservationService::notFound);
        Reservation reservation = reservations.lockByOrder(found.orderId()).orElseThrow(ReservationService::notFound);
        if (reservation.status() != ReservationStatus.CONFIRMED) {
            // 이미 환불 중이거나 끝났다. 다른 키로 다시 와도 명령을 또 보내지 않고 현재 상태를 돌려준다
            return new CancelResponse(reservationId, reservation.status());
        }
        requireBeforeDeadline(reservation);
        if (!reservations.transition(reservationId, ReservationStatus.CONFIRMED, ReservationStatus.CANCEL_REQUESTED)) {
            throw new IllegalStateException("예매 상태가 바뀌었다: " + reservationId);
        }
        events.cancelPayment(order, order.paymentId(), "USER_CANCEL");
        log.info("예매 취소를 요청했다: reservationId={}", reservationId);
        return new CancelResponse(reservationId, ReservationStatus.CANCEL_REQUESTED);
    }

    /** 공연 시작일(KST)의 00:00 전까지, 곧 전날 23:59:59까지 취소할 수 있다. */
    private void requireBeforeDeadline(Reservation reservation) {
        Instant startAt = inventory
                .findStartAt(reservation.sessionId())
                .orElseThrow(() -> new BusinessException(ReservationErrorCode.SESSION_SCHEDULE_UNKNOWN));
        Instant deadline = startAt.atZone(KST).toLocalDate().atStartOfDay(KST).toInstant();
        if (!clock.instant().isBefore(deadline)) {
            throw new BusinessException(ReservationErrorCode.CANCEL_DEADLINE_PASSED);
        }
    }

    private static BusinessException notFound() {
        return new BusinessException(ReservationErrorCode.RESERVATION_NOT_FOUND);
    }
}
