package io.github.kanabig.curtaincall.reservation.error;

import io.github.kanabig.curtaincall.common.web.error.ErrorCode;
import org.springframework.http.HttpStatus;

public enum ReservationErrorCode implements ErrorCode {
    SESSION_NOT_ON_SALE(HttpStatus.CONFLICT, "판매 중이 아닌 회차"),
    SEAT_ALREADY_HELD(HttpStatus.CONFLICT, "이미 점유되었거나 판매된 좌석"),
    PURCHASE_LIMIT_EXCEEDED(HttpStatus.CONFLICT, "회차당 매수 한도 초과"),
    HOLD_NOT_FOUND(HttpStatus.NOT_FOUND, "점유를 찾을 수 없음"),
    HOLD_EXPIRED(HttpStatus.CONFLICT, "만료된 점유"),
    HOLD_EXPIRING(HttpStatus.CONFLICT, "점유 만료까지 남은 시간이 짧아 결제를 시작할 수 없음"),
    ORDER_NOT_FOUND(HttpStatus.NOT_FOUND, "주문을 찾을 수 없음"),
    ORDER_ALREADY_EXISTS(HttpStatus.CONFLICT, "이미 주문이 만들어진 점유"),
    ORDER_NOT_PAYABLE(HttpStatus.CONFLICT, "결제를 시작할 수 없는 주문 상태");

    private final HttpStatus status;
    private final String message;

    ReservationErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    @Override
    public String code() {
        return name();
    }

    @Override
    public HttpStatus status() {
        return status;
    }

    @Override
    public String message() {
        return message;
    }
}
