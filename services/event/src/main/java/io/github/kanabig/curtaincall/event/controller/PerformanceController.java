package io.github.kanabig.curtaincall.event.controller;

import io.github.kanabig.curtaincall.event.dto.PerformanceQueryDtos.Filter;
import io.github.kanabig.curtaincall.event.dto.PerformanceQueryDtos.PerformanceDetail;
import io.github.kanabig.curtaincall.event.dto.PerformanceQueryDtos.PerformancePage;
import io.github.kanabig.curtaincall.event.dto.PerformanceQueryDtos.SessionDetail;
import io.github.kanabig.curtaincall.event.dto.PerformanceState;
import io.github.kanabig.curtaincall.event.service.PerformanceQueryService;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** [EVT-4, EVT-5] 공연·회차 조회. 공개 경로라 인증 헤더를 보지 않는다. */
@RestController
public class PerformanceController {

    private final PerformanceQueryService service;

    public PerformanceController(PerformanceQueryService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/performances")
    public PerformancePage list(
            @RequestParam(required = false) String region,
            @RequestParam(required = false) String genre,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) PerformanceState state,
            @RequestParam(required = false) Integer maxPrice,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + PerformanceQueryService.DEFAULT_SIZE) int size) {
        return service.list(new Filter(region, genre, from, to, state, maxPrice, keyword), page, size);
    }

    @GetMapping("/api/v1/performances/{id}")
    public PerformanceDetail get(@PathVariable long id) {
        return service.get(id);
    }

    @GetMapping("/api/v1/sessions/{id}")
    public SessionDetail getSession(@PathVariable long id) {
        return service.getSession(id);
    }
}
