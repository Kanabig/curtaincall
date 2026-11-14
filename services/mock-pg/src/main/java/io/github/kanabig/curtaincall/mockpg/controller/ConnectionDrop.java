package io.github.kanabig.curtaincall.mockpg.controller;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 응답을 유실시킨다. Connection: close와 실제보다 큰 Content-Length로 헤더만 내보내면 Tomcat이 본문 없이 연결을 닫는다.
 * 클라이언트는 승인 결과를 받지 못하고 I/O 예외를 만난다.
 */
@RestControllerAdvice
final class ConnectionDrop {

    /** 예외를 그대로 두면 기본 오류 처리가 본문을 덧붙이므로, 여기서 아무것도 쓰지 않고 끝낸다. */
    @ExceptionHandler(ResponseDropped.class)
    void handle() {}

    static void drop(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("application/json");
        response.setHeader("Connection", "close");
        response.setContentLength(1024);
        response.flushBuffer();
        throw new ResponseDropped();
    }

    static final class ResponseDropped extends RuntimeException {
        ResponseDropped() {
            super("injected response drop", null, false, false);
        }
    }
}
