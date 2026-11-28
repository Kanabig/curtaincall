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
        List<Hall> halls,
        String phone,
        String website,
        Boolean parkingLot,
        Accessibility accessibility,
        Amenities amenities) {

    /** 장애인 시설. 응답에 없으면 null이다. */
    public record Accessibility(Boolean elevator, Boolean parking, Boolean restroom, Boolean ramp) {}

    public record Amenities(Boolean cafe, Boolean restaurant, Boolean store, Boolean playroom, Boolean nursingRoom) {}

    public record Hall(String mt13id, String name, Integer seatScale, Integer disabledSeatScale) {}
}
