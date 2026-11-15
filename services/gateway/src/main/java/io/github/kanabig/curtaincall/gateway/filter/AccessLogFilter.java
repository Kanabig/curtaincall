package io.github.kanabig.curtaincall.gateway.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;

/**
 * [GW-3] 요청마다 한 줄: 메서드, 경로, 상태, 처리 시간(ms), 인증된 요청이면 사용자 ID. 쿼리 문자열과 헤더 값은 남기지 않는다.
 *
 * <p>로거 이름은 {@value #LOGGER_NAME}이다. {@code logging.level.curtaincall.access=OFF}로 끈다(부하 측정 때 로그 비용을 빼려고). 요청 ID는 이 필터가
 * 요청 처리가 끝난 뒤 다른 스레드에서 로그를 남기므로 헤더에서 읽어 MDC에 직접 넣는다.
 */
@Component
public class AccessLogFilter implements WebFilter, Ordered {

    public static final String LOGGER_NAME = "curtaincall.access";
    /** AuthFilter가 검증된 사용자 ID를 여기에 둔다. 요청 헤더는 하위 필터가 복사본을 만들어 바깥에서 보이지 않는다. */
    public static final String USER_ID_ATTRIBUTE = AccessLogFilter.class.getName() + ".userId";

    private static final Logger log = LoggerFactory.getLogger(LOGGER_NAME);
    private static final String MDC_KEY = RequestIdFilter.CONTEXT_KEY;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!log.isInfoEnabled() || exchange.getRequest().getPath().value().startsWith("/actuator")) {
            return chain.filter(exchange);
        }
        long start = System.nanoTime();
        return chain.filter(exchange).doFinally(signal -> log(exchange, signal, start));
    }

    private void log(ServerWebExchange exchange, SignalType signal, long start) {
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        HttpStatusCode status = exchange.getResponse().getStatusCode();
        String statusText =
                status != null ? String.valueOf(status.value()) : signal == SignalType.ON_ERROR ? "500" : "-";
        Object userId = exchange.getAttribute(USER_ID_ATTRIBUTE);
        String requestId = exchange.getRequest().getHeaders().getFirst(RequestIdFilter.HEADER);
        String previous = MDC.get(MDC_KEY);
        if (requestId != null) {
            MDC.put(MDC_KEY, requestId);
        }
        try {
            log.info(
                    "{} {} {} {}ms user={}",
                    exchange.getRequest().getMethod(),
                    exchange.getRequest().getPath().value(),
                    statusText,
                    elapsedMs,
                    userId == null ? "-" : userId);
        } finally {
            if (previous == null) {
                MDC.remove(MDC_KEY);
            } else {
                MDC.put(MDC_KEY, previous);
            }
        }
    }

    @Override
    public int getOrder() {
        // RequestIdFilter 바로 안쪽: 요청 ID가 정해진 뒤, 나머지 필터가 만든 응답까지 잰다
        return Ordered.HIGHEST_PRECEDENCE + 1;
    }
}
