package io.github.kanabig.curtaincall.reservation.controller;

import io.github.kanabig.curtaincall.reservation.dto.SeatStatusResponse;
import io.github.kanabig.curtaincall.reservation.service.SeatStatusService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** [RSV-10] 회차 좌석 판매 상태 조회. 인증은 gateway가 맡는다. */
@RestController
public class SeatStatusController {

    private final SeatStatusService service;

    public SeatStatusController(SeatStatusService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/sessions/{sessionId}/seat-status")
    public SeatStatusResponse seatStatus(@PathVariable long sessionId) {
        return service.get(sessionId);
    }
}
