package io.github.kanabig.curtaincall.event.client;

/**
 * 타임아웃, 연결 실패, 5xx. 호출 URL에 서비스 키가 들어 있어서 원인 예외를 붙이지 않는다(메시지와 스택에 URL이 남는다).
 */
public class KopisCallException extends RuntimeException {

    public KopisCallException(String message) {
        super(message);
    }
}
