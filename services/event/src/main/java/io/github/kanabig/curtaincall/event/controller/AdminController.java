package io.github.kanabig.curtaincall.event.controller;

import io.github.kanabig.curtaincall.event.config.KopisProperties;
import io.github.kanabig.curtaincall.event.dto.SessionPayload;
import io.github.kanabig.curtaincall.event.dto.SessionRequests.CreateRequest;
import io.github.kanabig.curtaincall.event.dto.SessionRequests.RegisterPerformanceRequest;
import io.github.kanabig.curtaincall.event.dto.SessionRequests.UpdateRequest;
import io.github.kanabig.curtaincall.event.entity.SaleSession;
import io.github.kanabig.curtaincall.event.service.KopisAdminService;
import io.github.kanabig.curtaincall.event.service.PerformanceAdminService;
import io.github.kanabig.curtaincall.event.service.PerformanceAdminService.Registered;
import io.github.kanabig.curtaincall.event.service.SessionAdminService;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** [ADM-1~4] 관리자 API. 접근 제한은 {@code AdminWebConfig}가 건다. */
@RestController
public class AdminController {

    private final PerformanceAdminService performances;
    private final SessionAdminService sessions;
    private final KopisAdminService kopis;
    private final KopisProperties properties;

    public AdminController(
            PerformanceAdminService performances,
            SessionAdminService sessions,
            KopisAdminService kopis,
            KopisProperties properties) {
        this.performances = performances;
        this.sessions = sessions;
        this.kopis = kopis;
        this.properties = properties;
    }

    /** 새로 등록하면 201, 이미 등록된 공연이면 200. */
    @PostMapping("/api/v1/admin/performances")
    public ResponseEntity<Map<String, Object>> registerPerformance(
            @Valid @RequestBody RegisterPerformanceRequest request) {
        Registered r = performances.register(request.kopisId());
        return ResponseEntity.status(r.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(Map.of("id", r.id(), "kopisId", r.kopisId(), "name", r.name(), "venueId", r.venueId()));
    }

    @PostMapping("/api/v1/admin/performances/{id}/sessions")
    @ResponseStatus(HttpStatus.CREATED)
    public SessionPayload createSession(@PathVariable long id, @Valid @RequestBody CreateRequest request) {
        return toPayload(sessions.create(id, request));
    }

    @PatchMapping("/api/v1/admin/sessions/{id}")
    public SessionPayload updateSession(@PathVariable long id, @Valid @RequestBody UpdateRequest request) {
        return toPayload(sessions.update(id, request));
    }

    @DeleteMapping("/api/v1/admin/sessions/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteSession(@PathVariable long id) {
        sessions.delete(id);
    }

    @PostMapping("/api/v1/admin/sessions/{id}/suspend")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void suspendSession(@PathVariable long id) {
        sessions.suspend(id);
    }

    @PostMapping("/api/v1/admin/kopis/sync")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Long> syncKopis() {
        return Map.of("runId", kopis.start());
    }

    private SessionPayload toPayload(SaleSession s) {
        return SessionPayload.of(s, properties.zone());
    }
}
