package io.github.kanabig.curtaincall.reservation.controller;

import io.github.kanabig.curtaincall.reservation.dto.HoldRequest;
import io.github.kanabig.curtaincall.reservation.dto.HoldResponse;
import io.github.kanabig.curtaincall.reservation.service.SeatHoldService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** [RSV-1~4] 좌석 점유와 해제. 사용자 ID는 gateway가 검증해 넘기는 {@code X-User-Id}다. 입장 토큰 검증도 gateway 몫이다. */
@RestController
public class SeatHoldController {

    private final SeatHoldService service;

    public SeatHoldController(SeatHoldService service) {
        this.service = service;
    }

    @PostMapping("/api/v1/sessions/{sessionId}/holds")
    @ResponseStatus(HttpStatus.CREATED)
    public HoldResponse hold(
            @PathVariable long sessionId,
            @RequestHeader("X-User-Id") long userId,
            @Valid @RequestBody HoldRequest request) {
        return HoldResponse.from(service.hold(sessionId, userId, request.seatIds()));
    }

    @DeleteMapping("/api/v1/holds/{holdId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void release(@PathVariable String holdId, @RequestHeader("X-User-Id") long userId) {
        service.release(holdId, userId);
    }
}
