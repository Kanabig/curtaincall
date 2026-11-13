package io.github.kanabig.curtaincall.common.web.error;

import java.util.LinkedHashMap;
import java.util.Map;

public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;
    private final transient Map<String, Object> properties = new LinkedHashMap<>();

    public BusinessException(ErrorCode errorCode) {
        this(errorCode, errorCode.message());
    }

    public BusinessException(ErrorCode errorCode, String detail) {
        super(detail);
        this.errorCode = errorCode;
    }

    /** 응답에 덧붙일 필드. 예: 충돌한 좌석 id 목록. */
    public BusinessException with(String name, Object value) {
        properties.put(name, value);
        return this;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    public Map<String, Object> properties() {
        return Map.copyOf(properties);
    }
}
