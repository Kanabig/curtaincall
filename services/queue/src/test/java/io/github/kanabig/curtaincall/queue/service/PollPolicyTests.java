package io.github.kanabig.curtaincall.queue.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PollPolicyTests {

    private final PollPolicy middle = new PollPolicy(() -> 0.5);

    @Test
    @DisplayName("앞 사람 수가 입장 속도 기준 10초 안이면 1초, 60초 안이면 3초, 그 이상이면 5초 간격이다")
    void intervalByAhead() {
        assertThat(middle.pollAfterMs(0, 50)).isEqualTo(1000);
        assertThat(middle.pollAfterMs(500, 50)).isEqualTo(1000);
        assertThat(middle.pollAfterMs(501, 50)).isEqualTo(3000);
        assertThat(middle.pollAfterMs(3000, 50)).isEqualTo(3000);
        assertThat(middle.pollAfterMs(3001, 50)).isEqualTo(5000);
    }

    @Test
    @DisplayName("지터는 기준 간격의 ±20% 안이다")
    void jitterBounds() {
        assertThat(new PollPolicy(() -> 0.0).pollAfterMs(100_000, 50)).isEqualTo(4000);
        assertThat(new PollPolicy(() -> 1.0).pollAfterMs(100_000, 50)).isEqualTo(6000);
        assertThat(new PollPolicy(() -> 0.0).pollAfterMs(0, 50)).isEqualTo(800);
    }

    @Test
    @DisplayName("예상 대기 시간은 앞 사람 수를 초당 입장 인원으로 나눈 값이다")
    void eta() {
        assertThat(middle.etaSeconds(3811, 20)).isEqualTo(190);
        assertThat(middle.etaSeconds(0, 50)).isZero();
    }
}
