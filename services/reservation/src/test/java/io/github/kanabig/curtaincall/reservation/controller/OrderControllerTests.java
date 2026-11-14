package io.github.kanabig.curtaincall.reservation.controller;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.kanabig.curtaincall.reservation.OrderIntegrationTest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** RSV-5 주문 API. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.kafka.listener.auto-startup=false",
            "curtaincall.inventory.scheduler.enabled=false",
            "curtaincall.order.scheduler.enabled=false",
            "curtaincall.outbox.relay.auto-start=false"
        })
class OrderControllerTests extends OrderIntegrationTest {

    @Autowired
    TestRestTemplate rest;

    /** 요청에 실어 보낼 입장 회차. null이면 헤더를 보내지 않는다. */
    Long entrySession = SESSION;

    @SuppressWarnings("unchecked")
    ResponseEntity<Map<String, Object>> call(
            HttpMethod method, String path, long userId, String idempotencyKey, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-User-Id", String.valueOf(userId));
        if (entrySession != null) {
            headers.set("X-Entry-Session-Id", String.valueOf(entrySession));
        }
        if (idempotencyKey != null) {
            headers.set("Idempotency-Key", idempotencyKey);
        }
        return (ResponseEntity<Map<String, Object>>)
                (ResponseEntity<?>) rest.exchange(path, method, new HttpEntity<>(body, headers), Map.class);
    }

    @Test
    @DisplayName("주문 생성은 201, 결제 시작은 202, 조회는 상태를 돌려주고 같은 키 재요청은 같은 응답이다")
    void orderFlow() {
        var hold = hold(USER, 1L, 2L);
        String key = key();

        var created = call(HttpMethod.POST, "/api/v1/orders", USER, key, Map.of("holdId", hold.holdId()));
        var replay = call(HttpMethod.POST, "/api/v1/orders", USER, key, Map.of("holdId", hold.holdId()));

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody()).containsEntry("status", "PENDING_PAYMENT");
        assertThat(created.getBody().get("amount")).isEqualTo(220000);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(replay.getBody()).isEqualTo(created.getBody());
        String orderId = (String) created.getBody().get("orderId");

        var paid = call(
                HttpMethod.POST,
                "/api/v1/orders/" + orderId + "/payment",
                USER,
                key(),
                Map.of("method", "CARD", "cardToken", "tok_approve"));
        assertThat(paid.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(paid.getBody()).containsEntry("status", "PAYMENT_REQUESTED");

        var polled = call(HttpMethod.GET, "/api/v1/orders/" + orderId, USER, null, null);
        assertThat(polled.getBody()).containsEntry("status", "PAYMENT_REQUESTED");
        assertThat(polled.getBody()).containsEntry("paymentStatus", "PROCESSING");
        assertThat((List<?>) polled.getBody().get("seatIds")).hasSize(2);
    }

    @Test
    @DisplayName("[QUE-5] 점유한 회차와 입장 토큰의 회차가 다르거나 입장 정보가 없으면 주문·결제 시작은 403 ENTRY_TOKEN_INVALID")
    void entrySessionMismatch() {
        var hold = hold(USER, 1L);
        Map<String, String> body = Map.of("holdId", hold.holdId());

        for (Long wrong : new Long[] {SESSION + 1, null}) {
            entrySession = wrong;
            var denied = call(HttpMethod.POST, "/api/v1/orders", USER, key(), body);
            assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(denied.getBody()).containsEntry("code", "ENTRY_TOKEN_INVALID");
        }

        entrySession = SESSION;
        var created = call(HttpMethod.POST, "/api/v1/orders", USER, key(), body);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String orderId = (String) created.getBody().get("orderId");
        Map<String, String> pay = Map.of("method", "CARD", "cardToken", "tok_approve");

        for (Long wrong : new Long[] {SESSION + 1, null}) {
            entrySession = wrong;
            var denied = call(HttpMethod.POST, "/api/v1/orders/" + orderId + "/payment", USER, key(), pay);
            assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(denied.getBody()).containsEntry("code", "ENTRY_TOKEN_INVALID");
        }
        // 조회에는 입장 토큰이 필요 없다
        assertThat(call(HttpMethod.GET, "/api/v1/orders/" + orderId, USER, null, null)
                        .getBody())
                .containsEntry("status", "PENDING_PAYMENT");
    }

    @Test
    @DisplayName("Idempotency-Key가 없거나 UUID가 아니면 400, 이미 주문된 점유는 409 ORDER_ALREADY_EXISTS")
    void errors() {
        var hold = hold(USER, 1L);
        Map<String, String> body = Map.of("holdId", hold.holdId());

        assertThat(call(HttpMethod.POST, "/api/v1/orders", USER, null, body).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        var invalid = call(HttpMethod.POST, "/api/v1/orders", USER, "not-a-uuid", body);
        assertThat(invalid.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(invalid.getBody()).containsEntry("code", "INVALID_REQUEST");

        assertThat(call(
                                HttpMethod.POST,
                                "/api/v1/orders",
                                USER,
                                UUID.randomUUID().toString(),
                                body)
                        .getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        var second =
                call(HttpMethod.POST, "/api/v1/orders", USER, UUID.randomUUID().toString(), body);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(second.getBody()).containsEntry("code", "ORDER_ALREADY_EXISTS");
    }
}
