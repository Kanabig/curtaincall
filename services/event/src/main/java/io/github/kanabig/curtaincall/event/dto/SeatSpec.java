package io.github.kanabig.curtaincall.event.dto;

/** 배치 템플릿의 좌석 하나. depth는 무대에서 먼 정도(0 맨 앞, 999 맨 뒤)다. */
public record SeatSpec(
        int floor, String section, String row, int number, int x, int y, int depth, boolean wheelchair) {}
