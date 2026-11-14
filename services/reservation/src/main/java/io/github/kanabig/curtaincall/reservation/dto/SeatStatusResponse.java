package io.github.kanabig.curtaincall.reservation.dto;

import java.util.List;

/**
 * 회차 좌석 판매 상태. 목록에 없는 좌석은 가능이다. {@code version}은 이 스냅샷을 만든 시각(epoch millis)이라, 같은 값이면 같은
 * 스냅샷이다.
 */
public record SeatStatusResponse(long version, List<Long> held, List<Long> sold) {}
