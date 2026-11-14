package io.github.kanabig.curtaincall.reservation.dto;

import io.github.kanabig.curtaincall.reservation.entity.SeatHold;
import java.time.Instant;
import java.util.List;

public record HoldResponse(String holdId, List<Long> seatIds, Instant expiresAt) {

    public static HoldResponse from(SeatHold hold) {
        return new HoldResponse(hold.holdId(), hold.seatIds(), hold.expiresAt());
    }
}
