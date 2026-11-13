package io.github.kanabig.curtaincall.common.web.trace;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter();

    @Test
    @DisplayName("요청의 X-Request-Id를 MDC와 응답 헤더에 그대로 쓴다")
    void keepsIncomingId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Request-Id", "abc-123");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> seen = new AtomicReference<>();

        filter.doFilter(request, response, new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                seen.set(MDC.get("requestId"));
            }
        });

        assertThat(seen.get()).isEqualTo("abc-123");
        assertThat(response.getHeader("X-Request-Id")).isEqualTo("abc-123");
        assertThat(MDC.get("requestId")).isNull();
    }

    @Test
    @DisplayName("헤더가 없으면 새 ID를 만든다")
    void generatesWhenMissing() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest(), response, new MockFilterChain());

        assertThat(response.getHeader("X-Request-Id")).isNotBlank();
    }

    @Test
    @DisplayName("허용하지 않는 문자가 든 헤더는 버리고 새 ID를 만든다")
    void replacesInvalidId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Request-Id", "bad id with spaces");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getHeader("X-Request-Id")).isNotEqualTo("bad id with spaces");
    }
}
