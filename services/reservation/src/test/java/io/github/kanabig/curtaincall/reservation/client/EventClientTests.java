package io.github.kanabig.curtaincall.reservation.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import io.github.kanabig.curtaincall.reservation.config.EventClientConfig;
import io.github.kanabig.curtaincall.reservation.config.EventClientProperties;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

class EventClientTests {

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("좌석 배치를 읽고 모르는 필드는 무시한다")
    void readsSeatMap() throws IOException {
        EventClient client = clientFor(0, Duration.ofSeconds(2));

        List<SeatMapEntry> seats = client.fetchSeatMap(7L);

        assertThat(seats).containsExactly(new SeatMapEntry(1, "VIP", 150_000), new SeatMapEntry(2, "R", 110_000));
    }

    @Test
    @DisplayName("응답이 read timeout보다 늦으면 예외로 끝난다")
    void readTimeoutFails() throws IOException {
        EventClient client = clientFor(1_500, Duration.ofMillis(200));

        assertThatThrownBy(() -> client.fetchSeatMap(7L)).isInstanceOf(ResourceAccessException.class);
    }

    private EventClient clientFor(long delayMs, Duration readTimeout) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        byte[] body = ("[{\"seatId\":1,\"floor\":1,\"section\":\"A\",\"grade\":\"VIP\",\"price\":150000},"
                        + "{\"seatId\":2,\"grade\":\"R\",\"price\":110000}]")
                .getBytes(StandardCharsets.UTF_8);
        server.createContext("/internal/sessions/7/seat-map", exchange -> {
            try {
                Thread.sleep(delayMs);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } catch (InterruptedException | IOException ignored) {
                // 클라이언트가 먼저 끊은 경우
            } finally {
                exchange.close();
            }
        });
        server.start();
        EventClientProperties properties = new EventClientProperties(
                "http://127.0.0.1:" + server.getAddress().getPort(), Duration.ofSeconds(1), readTimeout);
        RestClient restClient = new EventClientConfig().eventRestClient(RestClient.builder(), properties);
        return new EventClient(restClient);
    }
}
