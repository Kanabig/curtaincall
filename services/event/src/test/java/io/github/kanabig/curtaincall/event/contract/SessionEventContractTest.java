package io.github.kanabig.curtaincall.event.contract;

import io.github.kanabig.curtaincall.event.dto.SessionPayload;
import io.github.kanabig.curtaincall.event.dto.SessionPayload.PriceItem;
import io.github.kanabig.curtaincall.outbox.message.EventEnvelopes;
import io.github.kanabig.curtaincall.testsupport.EventContract;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;
import tools.jackson.databind.ObjectMapper;

/** event.session 이벤트의 와이어 형식. 골든은 libs/test-support의 contract/. */
@JsonTest
class SessionEventContractTest {

    @Autowired
    ObjectMapper mapper;

    private final SessionPayload payload = new SessionPayload(
            101L,
            7L,
            "FC000123-01",
            "2026-12-24T19:30:00+09:00",
            "2026-12-01T20:00:00+09:00",
            List.of(new PriceItem("R", 99000), new PriceItem("S", 66000)),
            200,
            5000);

    private String write(String type, Object body) {
        return new EventEnvelopes(mapper, EventContract.CLOCK).write(EventContract.EVENT_ID, type, "101", body);
    }

    @Test
    @DisplayName("SessionCreated")
    void created() {
        EventContract.assertMatches("SessionCreated", write("SessionCreated", payload));
    }

    @Test
    @DisplayName("SessionUpdated")
    void updated() {
        EventContract.assertMatches("SessionUpdated", write("SessionUpdated", payload));
    }

    @Test
    @DisplayName("SessionSuspended")
    void suspended() {
        EventContract.assertMatches("SessionSuspended", write("SessionSuspended", payload));
    }

    @Test
    @DisplayName("SessionDeleted")
    void deleted() {
        EventContract.assertMatches("SessionDeleted", write("SessionDeleted", Map.of("sessionId", 101L)));
    }
}
