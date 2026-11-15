package io.github.kanabig.curtaincall.event.client;

/** 응답 XML이 깨졌거나 필수 항목이 없다. 호출 자체는 성공했으므로 한도는 이미 썼다. */
public class KopisParseException extends RuntimeException {

    public KopisParseException(String message) {
        super(message);
    }

    public KopisParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
