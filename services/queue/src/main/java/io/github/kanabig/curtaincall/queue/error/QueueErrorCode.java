package io.github.kanabig.curtaincall.queue.error;

import io.github.kanabig.curtaincall.common.web.error.ErrorCode;
import org.springframework.http.HttpStatus;

public enum QueueErrorCode implements ErrorCode {
    SESSION_NOT_FOUND(HttpStatus.NOT_FOUND, "회차를 찾을 수 없음"),
    SALE_NOT_OPENED(HttpStatus.CONFLICT, "판매 오픈 전"),
    SESSION_NOT_ON_SALE(HttpStatus.CONFLICT, "판매 중이 아닌 회차"),
    QUEUE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "대기열을 일시적으로 사용할 수 없음");

    private final HttpStatus status;
    private final String message;

    QueueErrorCode(HttpStatus status, String message) {
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
