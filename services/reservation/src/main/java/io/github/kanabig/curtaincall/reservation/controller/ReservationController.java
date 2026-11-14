package io.github.kanabig.curtaincall.reservation.controller;

import io.github.kanabig.curtaincall.reservation.dto.CancelResponse;
import io.github.kanabig.curtaincall.reservation.dto.ReservationDetailResponse;
import io.github.kanabig.curtaincall.reservation.dto.ReservationPage;
import io.github.kanabig.curtaincall.reservation.entity.ReservationStatus;
import io.github.kanabig.curtaincall.reservation.service.ReservationService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** [RSV-8] 내 예매 목록·상세, [RSV-9] 예매 취소. 사용자 ID 검증은 gateway가 맡는다. */
@RestController
public class ReservationController {

    private final ReservationService service;

    public ReservationController(ReservationService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/reservations")
    public ReservationPage list(
            @RequestHeader("X-User-Id") long userId,
            @RequestParam(required = false) ReservationStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + ReservationService.DEFAULT_PAGE_SIZE) int size) {
        return service.list(userId, status, page, size);
    }

    @GetMapping("/api/v1/reservations/{reservationId}")
    public ReservationDetailResponse get(@PathVariable String reservationId, @RequestHeader("X-User-Id") long userId) {
        return service.get(userId, reservationId);
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
