package io.github.kanabig.curtaincall.common.web.error;

import org.springframework.http.HttpStatus;

/**
 * 에러 응답의 {@code code}가 되는 값. 서비스별 코드는 각 서비스가 이 인터페이스를 구현한 enum으로 둔다. 도메인 코드를 공통 모듈에 모으지 않기 위해서다.
 */
public interface ErrorCode {

    String code();

    HttpStatus status();

    String message();
}
