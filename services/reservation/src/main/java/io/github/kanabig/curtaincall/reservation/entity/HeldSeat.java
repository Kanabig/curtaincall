package io.github.kanabig.curtaincall.reservation.entity;

import java.time.Instant;

/** hold_id로 읽은 좌석 한 행. */
public record HeldSeat(long seatId, long sessionId, Long holderId, String status, int price, Instant expiresAt) {}
