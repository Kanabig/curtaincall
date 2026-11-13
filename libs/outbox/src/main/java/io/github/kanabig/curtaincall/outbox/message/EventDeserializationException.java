package io.github.kanabig.curtaincall.outbox.message;

/** 봉투를 읽을 수 없는 메시지. 다시 시도해도 같은 결과라 재시도 없이 DLT로 보낸다. */
public class EventDeserializationException extends RuntimeException {

    public EventDeserializationException(String message, Throwable cause) {
        super(message, cause);
    }

    public EventDeserializationException(String message) {
        super(message);
    }
}
