package io.github.kanabig.curtaincall.reservation.error;

import io.github.kanabig.curtaincall.common.web.error.ErrorCode;
import org.springframework.http.HttpStatus;

public enum ReservationErrorCode implements ErrorCode {
    SESSION_NOT_ON_SALE(HttpStatus.CONFLICT, "판매 중이 아닌 회차");

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
