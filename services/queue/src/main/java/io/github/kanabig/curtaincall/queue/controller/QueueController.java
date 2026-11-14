package io.github.kanabig.curtaincall.queue.controller;

import io.github.kanabig.curtaincall.queue.dto.QueueStatusResponse;
import io.github.kanabig.curtaincall.queue.service.QueueService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** [QUE-1~3] 사용자 ID는 gateway가 검증해 넘기는 {@code X-User-Id}다. */
@RestController
public class QueueController {

    private final QueueService service;

    public QueueController(QueueService service) {
        this.service = service;
    }

    /** 새로 들어오면 201, 이미 대기 중이면 같은 순번으로 200. */
    @PostMapping("/api/v1/queue/sessions/{sessionId}/entries")
    public ResponseEntity<QueueStatusResponse> enter(
            @PathVariable long sessionId, @RequestHeader("X-User-Id") long userId) {
        QueueService.Entered entered = service.enter(sessionId, userId);
        return ResponseEntity.status(entered.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(entered.response());
    }

    /** 호출이 곧 heartbeat다. */
    @GetMapping("/api/v1/queue/sessions/{sessionId}/entries/me")
    public QueueStatusResponse status(@PathVariable long sessionId, @RequestHeader("X-User-Id") long userId) {
        return service.status(sessionId, userId);
    }
}
