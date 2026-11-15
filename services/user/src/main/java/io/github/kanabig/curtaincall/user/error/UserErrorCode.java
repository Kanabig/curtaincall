package io.github.kanabig.curtaincall.user.error;

import io.github.kanabig.curtaincall.common.web.error.ErrorCode;
import org.springframework.http.HttpStatus;

public enum UserErrorCode implements ErrorCode {
    EMAIL_DUPLICATED(HttpStatus.CONFLICT, "이미 가입된 이메일"),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 올바르지 않음"),
    INVALID_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED, "사용할 수 없는 refresh token"),
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "회원을 찾을 수 없음");

    private final HttpStatus status;
    private final String message;

    UserErrorCode(HttpStatus status, String message) {
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
