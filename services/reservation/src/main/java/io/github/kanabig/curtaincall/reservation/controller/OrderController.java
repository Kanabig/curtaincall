package io.github.kanabig.curtaincall.reservation.controller;

import io.github.kanabig.curtaincall.reservation.dto.CreateOrderRequest;
import io.github.kanabig.curtaincall.reservation.dto.OrderDetailResponse;
import io.github.kanabig.curtaincall.reservation.dto.OrderResponse;
import io.github.kanabig.curtaincall.reservation.dto.PaymentStartedResponse;
import io.github.kanabig.curtaincall.reservation.dto.StartPaymentRequest;
import io.github.kanabig.curtaincall.reservation.service.OrderService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** [RSV-5] 주문 생성, 결제 시작, 주문 상태 조회. 사용자 ID와 입장 토큰 검증은 gateway가 맡고, 점유한 회차와 토큰의 회차가 같은지는 서비스가 본다. */
@RestController
public class OrderController {

    private final OrderService service;

    public OrderController(OrderService service) {
        this.service = service;
    }

    @PostMapping("/api/v1/orders")
    @ResponseStatus(HttpStatus.CREATED)
    public OrderResponse create(
            @RequestHeader("X-User-Id") long userId,
            @RequestHeader(value = "X-Entry-Session-Id", required = false) Long entrySessionId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreateOrderRequest request) {
        return service.createOrder(
                userId, entrySessionId, IdempotencyKeys.requireUuid(idempotencyKey), request.holdId());
    }

    @PostMapping("/api/v1/orders/{orderId}/payment")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public PaymentStartedResponse startPayment(
            @PathVariable String orderId,
            @RequestHeader("X-User-Id") long userId,
            @RequestHeader(value = "X-Entry-Session-Id", required = false) Long entrySessionId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody StartPaymentRequest request) {
        return service.startPayment(
                userId, entrySessionId, orderId, IdempotencyKeys.requireUuid(idempotencyKey), request.cardToken());
    }

    @GetMapping("/api/v1/orders/{orderId}")
    public OrderDetailResponse get(@PathVariable String orderId, @RequestHeader("X-User-Id") long userId) {
        return service.get(userId, orderId);
    }
}
