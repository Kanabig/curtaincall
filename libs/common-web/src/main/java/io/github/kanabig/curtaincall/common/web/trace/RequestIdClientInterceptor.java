package io.github.kanabig.curtaincall.common.web.trace;

import java.io.IOException;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/** 나가는 HTTP 호출에 현재 추적 ID를 X-Request-Id 헤더로 싣는다. 이미 헤더가 있으면 건드리지 않는다. */
public class RequestIdClientInterceptor implements ClientHttpRequestInterceptor {

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        String requestId = RequestIdContext.current();
        if (requestId != null && !request.getHeaders().containsKey(RequestIdContext.HEADER)) {
            request.getHeaders().set(RequestIdContext.HEADER, requestId);
        }
        return execution.execute(request, body);
    }
}
