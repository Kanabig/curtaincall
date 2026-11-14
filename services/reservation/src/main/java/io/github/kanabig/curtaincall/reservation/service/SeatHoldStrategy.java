package io.github.kanabig.curtaincall.reservation.service;

import io.github.kanabig.curtaincall.reservation.entity.SeatHold;
import java.util.List;

/**
 * 여러 좌석을 전부 잡거나 전부 실패하게 점유하는 방법. 구현은 {@code curtaincall.hold.strategy}로 고른다.
 *
 * <p>구현은 자기 트랜잭션을 직접 열고, 실패하면 {@code SEAT_ALREADY_HELD}, {@code PURCHASE_LIMIT_EXCEEDED}를 담은 BusinessException을 던진다. 좌석 ID는
 * 중복 없이 오름차순으로 들어온다.
 */
public interface SeatHoldStrategy {

    SeatHold hold(long sessionId, long userId, List<Long> seatIds);
}
