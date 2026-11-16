package io.github.kanabig.curtaincall.searchassist.client;

/** LLM 호출 실패(타임아웃, 응답 오류, 해석할 수 없는 응답). 원인 메시지에 사용자 입력을 넣지 않는다. */
public class ExtractionException extends RuntimeException {

    private final boolean timeout;

    public ExtractionException(String message, Throwable cause, boolean timeout) {
        super(message, cause);
        this.timeout = timeout;
    }

    public boolean isTimeout() {
        return timeout;
    }
}
