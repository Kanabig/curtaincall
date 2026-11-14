package io.github.kanabig.curtaincall.event.error;

import io.github.kanabig.curtaincall.common.web.error.ErrorCode;
import org.springframework.http.HttpStatus;

public enum EventErrorCode implements ErrorCode {
    FORBIDDEN(HttpStatus.FORBIDDEN, "관리자만 사용할 수 있음"),
    PERFORMANCE_NOT_FOUND(HttpStatus.NOT_FOUND, "공연을 찾을 수 없음"),
    PERFORMANCE_NOT_SYNCED(HttpStatus.CONFLICT, "상세 동기화가 끝나지 않은 공연"),
    HALL_LAYOUT_UNAVAILABLE(HttpStatus.CONFLICT, "좌석 배치를 만들 수 없는 공연장"),
    SESSION_NOT_FOUND(HttpStatus.NOT_FOUND, "회차를 찾을 수 없음"),
    SESSION_ALREADY_OPENED(HttpStatus.CONFLICT, "판매가 이미 열린 회차"),
    PRICE_GRADE_MISMATCH(HttpStatus.BAD_REQUEST, "가격 등급이 공연장 배치의 등급과 맞지 않음"),
    SESSION_EDIT_LOCKED(HttpStatus.CONFLICT, "판매 오픈 10분 전부터는 입장 속도와 활성 상한만 수정할 수 있음"),
    KOPIS_QUOTA_LOW(HttpStatus.CONFLICT, "KOPIS 남은 호출 수가 부족함"),
    KOPIS_SYNC_IN_PROGRESS(HttpStatus.CONFLICT, "KOPIS 동기화가 이미 실행 중(정기 또는 수동)");

    private final HttpStatus status;
    private final String message;

    EventErrorCode(HttpStatus status, String message) {
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
