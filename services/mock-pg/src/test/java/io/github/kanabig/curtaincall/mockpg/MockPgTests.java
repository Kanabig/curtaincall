package io.github.kanabig.curtaincall.mockpg;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** PAY-1, PAY-2, PAY-4, PAY-5가 기대하는 PG 동작과 장애 모드. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "mock-pg.timeout-hang-ms=1500")
class MockPgTests {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {};

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    @AfterEach
    void cleanUp() {
        rest.delete("/pg/admin/faults");
        rest.delete("/pg/admin/transactions");
    }

    @Test
    @DisplayName("tok_approve는 승인, tok_decline은 거절로 응답하고 알 수 없는 토큰은 400이다")
    void approvesAndDeclinesByToken() {
        ResponseEntity<Map<String, Object>> approved = approve("o-1", "tok_approve");
        ResponseEntity<Map<String, Object>> declined = approve("o-2", "tok_decline");
        ResponseEntity<Map<String, Object>> unknown = approve("o-3", "tok_unknown");

        assertThat(approved.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(approved.getBody()).containsEntry("status", "APPROVED").containsEntry("duplicate", false);
        assertThat(declined.getBody())
                .containsEntry("status", "DECLINED")
                .containsEntry("failureCode", "CARD_DECLINED");
        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("같은 merchantOrderId로 다시 승인하면 첫 거래를 돌려주고 새 거래를 만들지 않는다")
    void duplicateApprovalReturnsFirstTransaction() {
        Map<String, Object> first = approve("o-dup", "tok_approve").getBody();

        Map<String, Object> second = approve("o-dup", "tok_approve").getBody();

        assertThat(second).containsEntry("tid", first.get("tid")).containsEntry("duplicate", true);
        assertThat(second).containsEntry("status", "APPROVED");
    }

    @Test
    @DisplayName("같은 merchantOrderId로 동시에 승인해도 거래는 하나만 생긴다")
    void concurrentDuplicateApprovalsCreateOneTransaction() {
        try (ExecutorService executor = Executors.newFixedThreadPool(16)) {
            List<CompletableFuture<Object>> futures = IntStream.range(0, 32)
                    .mapToObj(i -> CompletableFuture.supplyAsync(
                            () -> approve("o-race", "tok_approve").getBody().get("tid"), executor))
                    .toList();

            long distinctTids =
                    futures.stream().map(CompletableFuture::join).distinct().count();

            assertThat(distinctTids).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("거래 조회는 승인된 거래를 돌려주고 없는 주문은 404이다")
    void queryTransaction() {
        approve("o-q", "tok_approve");

        ResponseEntity<Map<String, Object>> found = get("/pg/v1/transactions?merchantOrderId=o-q");
        ResponseEntity<Map<String, Object>> missing = get("/pg/v1/transactions?merchantOrderId=o-none");

        assertThat(found.getBody()).containsEntry("status", "APPROVED");
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("취소는 멱등이고 없는 거래는 404, 거절된 거래는 409이다")
    void cancelTransaction() {
        String tid = (String) approve("o-c", "tok_approve").getBody().get("tid");
        String declinedTid = (String) approve("o-c2", "tok_decline").getBody().get("tid");

        ResponseEntity<Map<String, Object>> first = post("/pg/v1/transactions/" + tid + "/cancel");
        ResponseEntity<Map<String, Object>> again = post("/pg/v1/transactions/" + tid + "/cancel");

        assertThat(first.getBody()).containsEntry("status", "CANCELLED");
        assertThat(again.getBody()).containsEntry("status", "CANCELLED");
        assertThat(again.getBody().get("cancelledAt")).isEqualTo(first.getBody().get("cancelledAt"));
        assertThat(post("/pg/v1/transactions/mpg_none/cancel").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(post("/pg/v1/transactions/" + declinedTid + "/cancel").getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("장애 설정을 API로 바꾸고 읽고 끌 수 있다. 범위를 벗어난 값은 400이다")
    void faultSettingsCanBeToggled() {
        ResponseEntity<Map<String, Object>> put =
                putFaults(Map.of("delayMs", 200, "failureRate", 0.5, "timeoutRate", 0.1, "dropResponseRate", 0.2));
        ResponseEntity<Map<String, Object>> read = get("/pg/admin/faults");
        ResponseEntity<Map<String, Object>> invalid = putFaults(Map.of("failureRate", 1.5));
        rest.delete("/pg/admin/faults");
        ResponseEntity<Map<String, Object>> reset = get("/pg/admin/faults");

        assertThat(put.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(read.getBody())
                .containsEntry("delayMs", 200)
                .containsEntry("failureRate", 0.5)
                .containsEntry("timeoutRate", 0.1)
                .containsEntry("dropResponseRate", 0.2);
        assertThat(invalid.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(reset.getBody())
                .containsEntry("delayMs", 0)
                .containsEntry("failureRate", 0.0)
                .containsEntry("timeoutRate", 0.0)
                .containsEntry("dropResponseRate", 0.0);
    }

    @Test
    @DisplayName("지연 모드는 승인 응답을 delayMs만큼 늦춘다")
    void delayModeSlowsApproval() {
        putFaults(Map.of("delayMs", 400));

        long start = System.nanoTime();
        ResponseEntity<Map<String, Object>> response = approve("o-delay", "tok_approve");
        long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(elapsedMs).isGreaterThanOrEqualTo(400);
    }

    @Test
    @DisplayName("실패 모드는 500을 응답하고 거래를 기록하지 않는다")
    void failureModeRejectsWithoutRecording() {
        putFaults(Map.of("failureRate", 1.0));

        ResponseEntity<Map<String, Object>> response = approve("o-fail", "tok_approve");
        rest.delete("/pg/admin/faults");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(get("/pg/v1/transactions?merchantOrderId=o-fail").getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("타임아웃 모드는 응답 없이 붙잡아 두고 거래를 기록하지 않는다")
    void timeoutModeHangsWithoutRecording() {
        putFaults(Map.of("timeoutRate", 1.0));

        assertThatThrownBy(() -> sendApproval("o-timeout", Duration.ofMillis(300)))
                .isInstanceOf(java.net.http.HttpTimeoutException.class);
        rest.delete("/pg/admin/faults");

        assertThat(get("/pg/v1/transactions?merchantOrderId=o-timeout").getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("응답 유실 모드는 승인을 기록하고 응답만 보내지 않는다")
    void dropResponseModeRecordsApprovalButLosesResponse() {
        putFaults(Map.of("dropResponseRate", 1.0));

        assertThatThrownBy(() -> sendApproval("o-drop", Duration.ofSeconds(3))).isInstanceOf(IOException.class);
        rest.delete("/pg/admin/faults");

        ResponseEntity<Map<String, Object>> found = get("/pg/v1/transactions?merchantOrderId=o-drop");
        assertThat(found.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(found.getBody()).containsEntry("status", "APPROVED");
    }

    @Test
    @DisplayName("승인 장애 모드가 켜져 있어도 거래 조회는 정상 응답한다")
    void faultsDoNotAffectQuery() {
        approve("o-live", "tok_approve");
        putFaults(Map.of("failureRate", 1.0, "timeoutRate", 0.0, "dropResponseRate", 1.0));

        ResponseEntity<Map<String, Object>> found = get("/pg/v1/transactions?merchantOrderId=o-live");

        assertThat(found.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private ResponseEntity<Map<String, Object>> approve(String merchantOrderId, String cardToken) {
        return rest.exchange(
                "/pg/v1/approvals",
                HttpMethod.POST,
                new org.springframework.http.HttpEntity<>(
                        Map.of("merchantOrderId", merchantOrderId, "amount", 10000, "cardToken", cardToken)),
                MAP);
    }

    private ResponseEntity<Map<String, Object>> get(String path) {
        return rest.exchange(path, HttpMethod.GET, null, MAP);
    }

    private ResponseEntity<Map<String, Object>> post(String path) {
        return rest.exchange(path, HttpMethod.POST, null, MAP);
    }

    private ResponseEntity<Map<String, Object>> putFaults(Map<String, Object> body) {
        return rest.exchange("/pg/admin/faults", HttpMethod.PUT, new org.springframework.http.HttpEntity<>(body), MAP);
    }

    /** 장애 모드는 클라이언트 쪽 예외로 드러나므로 JDK HttpClient로 직접 보낸다. */
    private HttpResponse<String> sendApproval(String merchantOrderId, Duration readTimeout) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/pg/v1/approvals"))
                .timeout(readTimeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"merchantOrderId\":\"" + merchantOrderId
                        + "\",\"amount\":10000,\"cardToken\":\"tok_approve\"}"))
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }
}
