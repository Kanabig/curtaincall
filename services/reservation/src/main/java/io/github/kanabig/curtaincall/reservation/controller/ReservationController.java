package io.github.kanabig.curtaincall.reservation.controller;

import io.github.kanabig.curtaincall.reservation.dto.CancelResponse;
import io.github.kanabig.curtaincall.reservation.service.ReservationService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** [RSV-9] 예매 취소. 사용자 ID 검증은 gateway가 맡는다. */
@RestController
public class ReservationController {

    private final ReservationService service;

    public ReservationController(ReservationService service) {
        this.service = service;
    }

    @PostMapping("/api/v1/reservations/{reservationId}/cancel")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public CancelResponse cancel(
            @PathVariable String reservationId,
            @RequestHeader("X-User-Id") long userId,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return service.cancel(userId, reservationId, IdempotencyKeys.requireUuid(idempotencyKey));
    }
}
