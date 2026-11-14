package io.github.kanabig.curtaincall.event.controller;

import io.github.kanabig.curtaincall.event.service.SeatMapService;
import io.github.kanabig.curtaincall.event.service.SeatMapService.Body;
import java.time.Duration;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** [EVT-10] 회차 좌석 배치도, [EVT-9] reservation이 재고를 만들 때 읽는 내부 API. 둘 다 같은 본문이다. */
@RestController
public class SeatMapController {

    private static final CacheControl PUBLIC_CACHE =
            CacheControl.maxAge(Duration.ofSeconds(60)).cachePublic();

    private final SeatMapService seatMaps;

    public SeatMapController(SeatMapService seatMaps) {
        this.seatMaps = seatMaps;
    }

    @GetMapping("/api/v1/sessions/{id}/seat-map")
    public ResponseEntity<byte[]> seatMap(
            @PathVariable long id, @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
        Body body = seatMaps.get(id);
        if (matches(ifNoneMatch, body.etag())) {
            return ResponseEntity.status(304)
                    .cacheControl(PUBLIC_CACHE)
                    .eTag(body.etag())
                    .build();
        }
        return ResponseEntity.ok()
                .cacheControl(PUBLIC_CACHE)
                .eTag(body.etag())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body.json());
    }

    @GetMapping("/internal/sessions/{id}/seat-map")
    public ResponseEntity<byte[]> internalSeatMap(@PathVariable long id) {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(seatMaps.get(id).json());
    }

    /** If-None-Match는 ETag 목록이거나 *일 수 있다. 약한 비교로 본다. */
    private static boolean matches(String header, String etag) {
        if (header == null) {
            return false;
        }
        if (header.strip().equals("*")) {
            return true;
        }
        for (String candidate : header.split(",")) {
            String c = candidate.strip();
            if (c.startsWith("W/")) {
                c = c.substring(2);
            }
            if (c.equals(etag)) {
                return true;
            }
        }
        return false;
    }
}
