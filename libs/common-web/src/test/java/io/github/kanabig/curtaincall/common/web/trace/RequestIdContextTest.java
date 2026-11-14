package io.github.kanabig.curtaincall.common.web.trace;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class RequestIdContextTest {

    @Test
    @DisplayName("open은 MDC에 ID를 넣고 close에서 이전 값으로 되돌린다")
    void restoresPreviousValue() {
        MDC.put("requestId", "outer");

        try (RequestIdContext.Scope ignored = RequestIdContext.open("inner")) {
            assertThat(RequestIdContext.current()).isEqualTo("inner");
        }

        assertThat(RequestIdContext.current()).isEqualTo("outer");
        MDC.clear();
    }

    @Test
    @DisplayName("이전 값이 없었으면 close 뒤 MDC가 비어 있다")
    void clearsWhenNoPrevious() {
        try (RequestIdContext.Scope ignored = RequestIdContext.open("only")) {
            assertThat(RequestIdContext.current()).isEqualTo("only");
        }

        assertThat(RequestIdContext.current()).isNull();
    }
}
