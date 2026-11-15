package io.github.kanabig.curtaincall.event.service;

import io.github.kanabig.curtaincall.event.dto.SeatSpec;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * EVT-6. 공연장 객석 수로 층·구역·열·번호·좌표를 만든다. 난수를 쓰지 않아 같은 입력이면 같은 배치가 나온다.
 *
 * <p>객석이 {@value #ONE_FLOOR_MAX}석을 넘으면 층을 나눈다(~{@value #TWO_FLOOR_MAX}석 2개 층, 그 위 3개 층). 층마다 한 열 좌석 수는 객석 수의 제곱근에 비례하고, 열이 넓으면 좌·중·우
 * 구역(A, B, C)으로 나눈다. 좌표는 구역 사이에 통로 한 칸을 둔 격자이고, 마지막 열이 모자라면 가운데로 맞춘다. 층은 1층부터 위로 이어 붙여 y를 매기므로 한 공연장 안에서 좌표가 겹치지
 * 않는다. 휠체어석은 1층 맨 뒤 열의 끝 번호부터 채운다.
 */
@Component
public class SeatLayoutGenerator {

    /** 이보다 큰 객석 수는 만들지 않는다. 좌석 배치도 응답과 회차별 재고 행 수가 시연 규모를 넘는다. */
    public static final int MAX_SEATS = 20_000;

    static final int ONE_FLOOR_MAX = 500;
    static final int TWO_FLOOR_MAX = 3_000;
    private static final int MIN_SEATS_PER_ROW = 8;
    private static final int MAX_SEATS_PER_ROW = 50;
    private static final int THREE_SECTION_MIN_WIDTH = 18;

    /**
     * @param seatScale 객석 수. 1 이상 {@link #MAX_SEATS} 이하여야 한다
     * @param disabledSeatScale 객석 수 안의 휠체어석 수. null, 음수, 객석 수의 10%를 넘는 값은 0으로 본다
     */
    public List<SeatSpec> generate(int seatScale, Integer disabledSeatScale) {
        if (seatScale < 1 || seatScale > MAX_SEATS) {
            throw new IllegalArgumentException("seatScale out of range: " + seatScale);
        }
        int wheelchair = disabledSeatScale == null || disabledSeatScale < 0 || disabledSeatScale > seatScale / 10
                ? 0
                : disabledSeatScale;
        int[] floorSeats = splitFloors(seatScale);
        List<SeatSpec> seats = new ArrayList<>(seatScale);
        int ordinal = 0;
        int yBase = 0;
        for (int floorIndex = 0; floorIndex < floorSeats.length; floorIndex++) {
            int total = floorSeats[floorIndex];
            int perRow = seatsPerRow(total);
            int sections = perRow >= THREE_SECTION_MIN_WIDTH ? 3 : 1;
            int rows = (total + perRow - 1) / perRow;
            int remaining = total;
            for (int r = 0; r < rows; r++) {
                int count = Math.min(perRow, remaining);
                int offset = (perRow - count) / 2;
                int depth = (int) ((long) ordinal * 1000 / seatScale);
                for (int n = 1; n <= count; n++) {
                    int column = offset + n - 1;
                    int section = column * sections / perRow;
                    seats.add(new SeatSpec(
                            floorIndex + 1,
                            String.valueOf((char) ('A' + section)),
                            rowLabel(r),
                            n,
                            column + 1 + section,
                            yBase + r + 1,
                            depth,
                            false));
                }
                ordinal += count;
                remaining -= count;
            }
            yBase += rows;
        }
        return markWheelchair(seats, wheelchair);
    }

    /** 1층 맨 뒤 열 끝 번호부터 휠체어석으로 바꾼다. 1층 좌석 수가 휠체어석 수보다 적으면(객석 수의 10% 상한이 있어 거의 없다) 있는 만큼만 바꾼다. */
    private static List<SeatSpec> markWheelchair(List<SeatSpec> seats, int wheelchair) {
        int lastFirstFloor = -1;
        for (int i = 0; i < seats.size() && seats.get(i).floor() == 1; i++) {
            lastFirstFloor = i;
        }
        List<SeatSpec> result = new ArrayList<>(seats);
        for (int i = 0; i < wheelchair && lastFirstFloor - i >= 0; i++) {
            SeatSpec s = result.get(lastFirstFloor - i);
            result.set(
                    lastFirstFloor - i,
                    new SeatSpec(s.floor(), s.section(), s.row(), s.number(), s.x(), s.y(), s.depth(), true));
        }
        return result;
    }

    private static int[] splitFloors(int seatScale) {
        if (seatScale <= ONE_FLOOR_MAX) {
            return new int[] {seatScale};
        }
        if (seatScale <= TWO_FLOOR_MAX) {
            int upper = seatScale * 35 / 100;
            return new int[] {seatScale - upper, upper};
        }
        int third = seatScale * 20 / 100;
        int second = seatScale * 30 / 100;
        return new int[] {seatScale - second - third, second, third};
    }

    private static int seatsPerRow(int floorSeats) {
        int width = (int) Math.round(Math.sqrt(floorSeats * 2.0));
        return Math.max(Math.min(MIN_SEATS_PER_ROW, floorSeats), Math.min(MAX_SEATS_PER_ROW, width));
    }

    /** 0 → A, 25 → Z, 26 → AA. */
    static String rowLabel(int index) {
        StringBuilder label = new StringBuilder();
        int n = index;
        do {
            label.insert(0, (char) ('A' + n % 26));
            n = n / 26 - 1;
        } while (n >= 0);
        return label.toString();
    }
}
