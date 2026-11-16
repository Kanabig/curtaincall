package io.github.kanabig.curtaincall.searchassist.service;

/** 검증에 실패한 필드 이름만 담는다. 값은 사용자 입력에서 왔으므로 메시지에 넣지 않는다. */
public class InvalidConditionsException extends RuntimeException {

    private final String field;

    public InvalidConditionsException(String field) {
        super("invalid field: " + field);
        this.field = field;
    }

    public String field() {
        return field;
    }
}
