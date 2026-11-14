package io.github.kanabig.curtaincall.reservation.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** event 내부 API가 내려주는 좌석 한 건. 재고에 필요한 필드만 읽는다. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SeatMapEntry(long seatId, String grade, int price) {}
