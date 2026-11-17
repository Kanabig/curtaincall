package io.github.kanabig.curtaincall.gateway.filter;

import java.net.ConnectException;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebExceptionHandler;
import reactor.core.publisher.Mono;

/** 하위 서비스 연결·응답 실패(502, 503, 504)를 다른 오류와 같은 Problem Details 본문으로 바꾼다. */
@Component
@Order(-2)
public class UpstreamErrorHandler implements WebExceptionHandler {

    private final ProblemWriter problemWriter;

    public UpstreamErrorHandler(ProblemWriter problemWriter) {
        this.problemWriter = problemWriter;
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        if (exchange.getResponse().isCommitted()) {
            return Mono.error(ex);
        }
        // 연결 실패는 ResponseStatusException으로 감싸지지 않고 그대로 올라온다
        if (hasCause(ex, ConnectException.class)) {
            return unavailable(exchange);
        }
        if (!(ex instanceof ResponseStatusException rse)) {
            return Mono.error(ex);
        }
        HttpStatusCode status = rse.getStatusCode();
        if (status.isSameCodeAs(HttpStatus.GATEWAY_TIMEOUT)) {
            return problemWriter.write(
                    exchange, HttpStatus.GATEWAY_TIMEOUT, "UPSTREAM_TIMEOUT", "하위 서비스가 제한 시간 안에 응답하지 않았다");
        }
        if (status.isSameCodeAs(HttpStatus.SERVICE_UNAVAILABLE) || status.isSameCodeAs(HttpStatus.BAD_GATEWAY)) {
            return unavailable(exchange);
        }
        return Mono.error(ex);
    }

    private Mono<Void> unavailable(ServerWebExchange exchange) {
        return problemWriter.write(
                exchange, HttpStatus.SERVICE_UNAVAILABLE, "UPSTREAM_UNAVAILABLE", "하위 서비스에 연결할 수 없다");
    }

    private static boolean hasCause(Throwable ex, Class<? extends Throwable> type) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (type.isInstance(t)) {
                return true;
            }
        }
        return false;
    }
}
