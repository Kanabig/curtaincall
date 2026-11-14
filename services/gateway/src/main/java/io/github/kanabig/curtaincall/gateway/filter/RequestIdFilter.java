package io.github.kanabig.curtaincall.gateway.filter;

import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * [GW-3] X-Request-Id가 없거나 형식이 맞지 않으면 새로 만들어 서비스로 넘기고 응답에도 싣는다. 로그에는 Reactor Context를 거쳐 MDC로 남는다.
 *
 * <p>servlet 기반인 common-web의 필터를 쓸 수 없어 같은 규칙(허용 문자와 길이)을 따로 둔다.
 */
@Component
public class RequestIdFilter implements WebFilter, Ordered {

    public static final String HEADER = "X-Request-Id";
    public static final String CONTEXT_KEY = "requestId";

    // 로그 위조(개행 삽입 등)를 막으려고 허용 문자와 길이를 제한한다
    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String incoming = exchange.getRequest().getHeaders().getFirst(HEADER);
        String requestId = incoming != null && VALID.matcher(incoming).matches()
                ? incoming
                : UUID.randomUUID().toString();

        // 서비스 응답이 같은 헤더를 실어 와도 gateway가 정한 값이 남도록 커밋 직전에 덮어쓴다
        exchange.getResponse().beforeCommit(() -> {
            exchange.getResponse().getHeaders().set(HEADER, requestId);
            return Mono.empty();
        });
        ServerWebExchange mutated = exchange.mutate()
                .request(request -> request.headers(headers -> headers.set(HEADER, requestId)))
                .build();
        return chain.filter(mutated).contextWrite(context -> context.put(CONTEXT_KEY, requestId));
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
