package io.github.kanabig.curtaincall.common.web.trace;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/** X-Request-Id를 MDC에 넣고 응답에도 돌려준다. 헤더가 없거나 형식이 맞지 않으면 새로 만든다. */
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = RequestIdContext.HEADER;
    public static final String MDC_KEY = RequestIdContext.MDC_KEY;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = request.getHeader(HEADER);
        if (!RequestIdContext.isValid(requestId)) {
            requestId = RequestIdContext.generate();
        }
        MDC.put(MDC_KEY, requestId);
        response.setHeader(HEADER, requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
