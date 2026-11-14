package io.github.kanabig.curtaincall.event.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.kanabig.curtaincall.event.dto.SeatSpec;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** EVT-6: 좌석 수 = 객석 수, 좌표 중복 없음, 같은 입력이면 같은 배치. */
class SeatLayoutGeneratorTests {

    private final SeatLayoutGenerator generator = new SeatLayoutGenerator();

    /** 1, 70(재즈클럽 그루브), 126(엠스테이지 1관), 485(춘천 대극장), 1458(춘천인형극장 전체)과 층 나누기 경계. */
    @ParameterizedTest
    @ValueSource(ints = {1, 7, 70, 126, 485, 500, 501, 1458, 3000, 3001, 20_000})
    @DisplayName("좌석 수가 객석 수와 같고 좌표와 (층, 열, 번호)가 겹치지 않는다")
    void countAndUniqueness(int seatScale) {
        List<SeatSpec> seats = generator.generate(seatScale, null);

        assertThat(seats).hasSize(seatScale);
        Set<String> coordinates = new HashSet<>();
        Set<String> positions = new HashSet<>();
        for (SeatSpec seat : seats) {
            assertThat(coordinates.add(seat.x() + "," + seat.y()))
                    .as("좌표 %s", seat)
                    .isTrue();
            assertThat(positions.add(seat.floor() + "/" + seat.row() + "/" + seat.number()))
                    .as("위치 %s", seat)
                    .isTrue();
            assertThat(seat.x()).isPositive();
            assertThat(seat.y()).isPositive();
            assertThat(seat.depth()).isBetween(0, 999);
        }
    }

    @Test
    @DisplayName("무대에 가까운 열일수록 depth가 작고 y가 작다")
    void frontRowsComeFirst() {
        List<SeatSpec> seats = generator.generate(1458, null);

        for (int i = 1; i < seats.size(); i++) {
            assertThat(seats.get(i).y()).isGreaterThanOrEqualTo(seats.get(i - 1).y());
            assertThat(seats.get(i).depth())
                    .isGreaterThanOrEqualTo(seats.get(i - 1).depth());
        }
        assertThat(seats.get(0).depth()).isZero();
        assertThat(seats.get(0).row()).isEqualTo("A");
        assertThat(seats.get(0).floor()).isEqualTo(1);
        assertThat(seats.get(seats.size() - 1).floor()).isEqualTo(2);
    }

    @Test
    @DisplayName("층 수는 객석 수에 따라 1~3개이고 열이 넓으면 구역이 셋으로 나뉜다")
    void floorsAndSections() {
        assertThat(generator.generate(70, null)).extracting(SeatSpec::floor).containsOnly(1);
        assertThat(generator.generate(70, null)).extracting(SeatSpec::section).containsOnly("A");
        assertThat(generator.generate(1000, null)).extracting(SeatSpec::floor).containsOnly(1, 2);
        assertThat(generator.generate(485, null)).extracting(SeatSpec::section).contains("A", "B", "C");
        assertThat(generator.generate(5000, null)).extracting(SeatSpec::floor).containsOnly(1, 2, 3);
    }

    @Test
    @DisplayName("휠체어석은 disabledseatscale만큼, 1층 맨 뒤 열 끝 번호부터 표시한다")
    void wheelchairSeats() {
        List<SeatSpec> seats = generator.generate(485, 3);

        assertThat(seats).filteredOn(SeatSpec::wheelchair).hasSize(3);
        List<SeatSpec> firstFloor = seats.stream().filter(s -> s.floor() == 1).toList();
        assertThat(firstFloor.subList(firstFloor.size() - 3, firstFloor.size())).allMatch(SeatSpec::wheelchair);
        assertThat(generator.generate(485, 0)).noneMatch(SeatSpec::wheelchair);
        assertThat(generator.generate(485, null)).noneMatch(SeatSpec::wheelchair);
    }

    @Test
    @DisplayName("휠체어석 수가 음수이거나 객석 수의 10%를 넘으면 무시한다")
    void invalidWheelchairCountIsIgnored() {
        assertThat(generator.generate(100, -1)).noneMatch(SeatSpec::wheelchair);
        assertThat(generator.generate(100, 11)).noneMatch(SeatSpec::wheelchair);
        assertThat(generator.generate(100, 10)).filteredOn(SeatSpec::wheelchair).hasSize(10);
    }

    @Test
    @DisplayName("같은 입력이면 같은 배치가 나온다")
    void deterministic() {
        assertThat(generator.generate(1458, 3)).isEqualTo(new SeatLayoutGenerator().generate(1458, 3));
    }

    @Test
    @DisplayName("객석 수가 0 이하이거나 상한을 넘으면 만들지 않는다")
    void outOfRange() {
        assertThatThrownBy(() -> generator.generate(0, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> generator.generate(-5, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> generator.generate(SeatLayoutGenerator.MAX_SEATS + 1, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("열 이름은 A~Z 다음 AA로 이어진다")
    void rowLabels() {
        assertThat(SeatLayoutGenerator.rowLabel(0)).isEqualTo("A");
        assertThat(SeatLayoutGenerator.rowLabel(25)).isEqualTo("Z");
        assertThat(SeatLayoutGenerator.rowLabel(26)).isEqualTo("AA");
        assertThat(SeatLayoutGenerator.rowLabel(27)).isEqualTo("AB");
    }
}
