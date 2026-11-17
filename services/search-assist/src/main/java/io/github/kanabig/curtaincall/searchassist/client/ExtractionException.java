package io.github.kanabig.curtaincall.searchassist.client;

/**
 * LLM 호출 실패(타임아웃, 429, 인증·HTTP 오류, 해석할 수 없는 응답). 원인 메시지에 사용자 입력과 키를 넣지 않는다. 사유와 HTTP 상태는 로그·평가 실행기가 쓴다.
 */
public class ExtractionException extends RuntimeException {

    public enum Reason {
        TIMEOUT,
        /** 제공자가 429(한도 초과)로 거절했다. 재시도하지 않고 따로 센다. */
        RATE_LIMITED,
        /** 401·403. 키가 틀렸거나 권한이 없다. */
        AUTH,
        /** 그 밖의 HTTP 오류 상태. */
        HTTP_ERROR,
        /** 연결 실패 등 응답을 받지 못함. */
        CONNECTION,
        /** 응답은 받았지만 해석할 수 없음(JSON 없음, 형식 오류). */
        BAD_RESPONSE,
        UNKNOWN
    }

    private final Reason reason;
    private final Integer status;

    public ExtractionException(String message, Throwable cause, Reason reason, Integer status) {
        super(message, cause);
        this.reason = reason;
        this.status = status;
    }

    public ExtractionException(String message, Throwable cause, boolean timeout) {
        this(message, cause, timeout ? Reason.TIMEOUT : Reason.UNKNOWN, null);
    }

    public static ExtractionException badResponse(String message, Throwable cause) {
        return new ExtractionException(message, cause, Reason.BAD_RESPONSE, null);
    }

    public Reason reason() {
        return reason;
    }

    /** HTTP 응답을 받았을 때의 상태 코드. 없으면 null. */
    public Integer status() {
        return status;
    }

    public boolean isTimeout() {
        return reason == Reason.TIMEOUT;
    }

    public boolean isRateLimited() {
        return reason == Reason.RATE_LIMITED;
    }

    public boolean isAuthFailure() {
        return reason == Reason.AUTH;
    }
}
