package io.github.kanabig.curtaincall.event.dto;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;

/**
 * 회차 규칙. slots는 요일별 시작 시각이다. holidayTimes는 공휴일 회차 시각인데 공휴일 달력이 없어 후보 생성에는 쓰지 않는다. parsed가 false면 기본 규칙이다.
 */
public record SessionRule(List<Slot> slots, List<LocalTime> holidayTimes, boolean parsed) {

    public record Slot(DayOfWeek day, LocalTime time) {}

    /** 파싱에 실패했을 때 쓰는 시연용 규칙: 수~금 19:30, 토 14:00·18:00, 일 15:00. */
    public static SessionRule defaults() {
        List<Slot> slots = new java.util.ArrayList<>();
        for (DayOfWeek day : List.of(DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)) {
            slots.add(new Slot(day, LocalTime.of(19, 30)));
        }
        slots.add(new Slot(DayOfWeek.SATURDAY, LocalTime.of(14, 0)));
        slots.add(new Slot(DayOfWeek.SATURDAY, LocalTime.of(18, 0)));
        slots.add(new Slot(DayOfWeek.SUNDAY, LocalTime.of(15, 0)));
        return new SessionRule(List.copyOf(slots), List.of(), false);
    }
}
