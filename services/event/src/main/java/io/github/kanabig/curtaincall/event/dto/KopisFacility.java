package io.github.kanabig.curtaincall.event.dto;

import java.math.BigDecimal;
import java.util.List;

/** 공연시설상세. seatScale 등 숫자는 비어 있거나 숫자가 아니면 null이다. */
public record KopisFacility(
        String mt10id,
        String name,
        Integer hallCount,
        Integer seatScale,
        String address,
        BigDecimal latitude,
        BigDecimal longitude,
        List<Hall> halls) {

    public record Hall(String mt13id, String name, Integer seatScale, Integer disabledSeatScale) {}
}
