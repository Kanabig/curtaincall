package io.github.kanabig.curtaincall.searchassist.client;

/** LLM 호출 실패(타임아웃, 429, 응답 오류, 해석할 수 없는 응답). 원인 메시지에 사용자 입력을 넣지 않는다. */
public class ExtractionException extends RuntimeException {

    private final boolean timeout;
    private final boolean rateLimited;

    public ExtractionException(String message, Throwable cause, boolean timeout) {
        this(message, cause, timeout, false);
    }

    public ExtractionException(String message, Throwable cause, boolean timeout, boolean rateLimited) {
        super(message, cause);
        this.timeout = timeout;
        this.rateLimited = rateLimited;
    }

    public boolean isTimeout() {
        return timeout;
    }

    /** 제공자가 429(한도 초과)로 거절했다. 재시도하지 않고 따로 센다. */
    public boolean isRateLimited() {
        return rateLimited;
    }
}
