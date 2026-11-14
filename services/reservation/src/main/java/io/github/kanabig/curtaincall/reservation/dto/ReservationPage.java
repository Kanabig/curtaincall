package io.github.kanabig.curtaincall.reservation.dto;

import java.util.List;

/** offset 페이지네이션 응답. page는 0부터 시작한다. */
public record ReservationPage(
        List<ReservationResponse> items, int page, int size, long totalElements, int totalPages) {}
