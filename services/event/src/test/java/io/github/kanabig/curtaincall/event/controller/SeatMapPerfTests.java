package io.github.kanabig.curtaincall.event.controller;

import io.github.kanabig.curtaincall.event.EventIntegrationTest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;

/**
 * [EVT-10] 1만 석 좌석 배치도의 응답 크기와 시간. 기본 빌드에서는 빠지고 다음 명령으로 돌린다.
 *
 * <pre>./gradlew :services:event:test -Pperf --tests '*SeatMapPerfTests' -i | grep PERF</pre>
 *
 * 같은 JVM의 로컬 HTTP 클라이언트로 재므로 gateway와 네트워크는 포함하지 않는다. 다른 부하가 없는 상태에서 돌린다.
 */
@Tag("perf")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "curtaincall.kopis.scheduler.enabled=false",
            "curtaincall.kopis.bootstrap=false",
            "curtaincall.outbox.relay.auto-start=false"
        })
class SeatMapPerfTests extends EventIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(SeatMapPerfTests.class);
    private static final int REQUESTS = 300;

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    @Test
    void measureTenThousandSeats() throws Exception {
        jdbc.update(
                "INSERT INTO hall (mt13id, mt10id, name, seat_scale, disabled_seat_scale) VALUES ('H-10K', 'F1', 'h', 10000, 100)");
        jdbc.update(
                "INSERT INTO performance (mt20id, name, mt10id, mt13id, start_date, end_date, detail_status, created_at, updated_at)"
                        + " VALUES ('PF1', 'p', 'F1', 'H-10K', '2026-12-01', '2026-12-31', 'DONE', NOW(6), NOW(6))");
        HttpHeaders admin = new HttpHeaders();
        admin.setContentType(MediaType.APPLICATION_JSON);
        admin.set("X-User-Role", "ADMIN");
        rest.exchange(
                "/api/v1/admin/performances",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("kopisId", "PF1"), admin),
                String.class);
        Instant open = Instant.now().plus(Duration.ofDays(1));
        String session = "{\"startAt\":\"" + open.plus(Duration.ofDays(7)) + "\",\"saleOpenAt\":\"" + open
                + "\",\"prices\":[{\"grade\":\"VIP\",\"price\":150000},{\"grade\":\"R\",\"price\":120000},"
                + "{\"grade\":\"S\",\"price\":90000},{\"grade\":\"A\",\"price\":60000}],"
                + "\"admissionPerSecond\":50,\"maxActiveUsers\":500}";
        rest.exchange(
                "/api/v1/admin/performances/1/sessions",
                HttpMethod.POST,
                new HttpEntity<>(session, admin),
                String.class);

        HttpClient client = HttpClient.newHttpClient();
        URI uri = URI.create("http://localhost:" + port + "/api/v1/sessions/1/seat-map");
        long start = System.nanoTime();
        HttpResponse<byte[]> cold =
                client.send(HttpRequest.newBuilder(uri).build(), HttpResponse.BodyHandlers.ofByteArray());
        log.info("PERF cold(DB 조회+직렬화+압축) {}ms plain={}B", fmt(ms(start)), cold.body().length);
        String etag = cold.headers().firstValue("etag").orElseThrow();

        for (String encoding : new String[] {null, "gzip"}) {
            double[] times = new double[REQUESTS];
            int size = 0;
            for (int i = 0; i < REQUESTS; i++) {
                HttpRequest.Builder request = HttpRequest.newBuilder(uri);
                if (encoding != null) {
                    request.header("Accept-Encoding", encoding);
                }
                long t = System.nanoTime();
                size = client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray())
                        .body()
                        .length;
                times[i] = ms(t);
            }
            report("warm encoding=" + encoding + " bytes=" + size, times);
        }
        double[] notModified = new double[REQUESTS];
        for (int i = 0; i < REQUESTS; i++) {
            long t = System.nanoTime();
            int status = client.send(
                            HttpRequest.newBuilder(uri)
                                    .header("If-None-Match", etag)
                                    .build(),
                            HttpResponse.BodyHandlers.discarding())
                    .statusCode();
            if (status != 304) {
                throw new IllegalStateException("304가 아니다: " + status);
            }
            notModified[i] = ms(t);
        }
        report("304", notModified);
    }

    private static String fmt(double ms) {
        return String.format("%.1f", ms);
    }

    private static double ms(long startNanos) {
        return (System.nanoTime() - startNanos) / 1e6;
    }

    private static void report(String label, double[] times) {
        Arrays.sort(times);
        log.info(
                "PERF {} n={} p50={}ms p95={}ms max={}ms",
                label,
                times.length,
                fmt(times[times.length / 2]),
                fmt(times[times.length * 95 / 100]),
                fmt(times[times.length - 1]));
    }
}
