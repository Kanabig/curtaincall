package io.github.kanabig.curtaincall.queue.messaging;

/** 봉투나 payload를 읽을 수 없는 회차 이벤트. 다시 시도해도 같은 결과라 바로 DLT로 보낸다. */
public class InvalidSessionEventException extends RuntimeException {

    public InvalidSessionEventException(String message) {
        super(message);
    }

    public InvalidSessionEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
