package io.github.kanabig.curtaincall.reservation.dto;

import io.github.kanabig.curtaincall.reservation.entity.ReservationStatus;

/** 예매 취소 응답. 환불이 끝나기 전에는 CANCEL_REQUESTED, 같은 요청이 환불 뒤에 다시 오면 CANCELLED. */
public record CancelResponse(String reservationId, ReservationStatus status) {}
