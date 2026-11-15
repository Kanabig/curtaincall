package io.github.kanabig.curtaincall.queue.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DltPropertiesTests {

    private final DltProperties dlt = new DltProperties(Map.of("event.session", "event.session.queue.DLT"));

    @Test
    @DisplayName("지정한 토픽만 DLT 이름을 덮어쓰고 나머지는 {토픽}.DLT다")
    void overridesOnlyConfiguredTopics() {
        assertThat(dlt.dltTopic("event.session")).isEqualTo("event.session.queue.DLT");
        assertThat(dlt.dltTopic("reservation.session")).isEqualTo("reservation.session.DLT");
    }
}
