package io.github.kanabig.curtaincall.reservation.entity;

import java.time.Instant;
import java.util.List;

/** 점유 결과. */
public record SeatHold(String holdId, List<Long> seatIds, Instant expiresAt) {}
