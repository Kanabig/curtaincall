package io.github.kanabig.curtaincall.reservation.client;

import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** event 서비스 내부 API. 실패는 예외로 올려 보낸다. 재시도는 호출한 컨슈머의 백오프가 맡는다. */
@Component
public class EventClient {

    private final RestClient restClient;

    public EventClient(@Qualifier("eventRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    public List<SeatMapEntry> fetchSeatMap(long sessionId) {
        List<SeatMapEntry> seats = restClient
                .get()
                .uri("/internal/sessions/{id}/seat-map", sessionId)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
        return seats == null ? List.of() : seats;
    }
}
