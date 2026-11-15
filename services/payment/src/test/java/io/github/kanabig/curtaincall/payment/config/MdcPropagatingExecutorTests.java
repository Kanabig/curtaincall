package io.github.kanabig.curtaincall.payment.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.kanabig.curtaincall.common.web.trace.RequestIdContext;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/** [PAY-2] PG 호출 스레드에서도 요청 ID가 이어져 결과 이벤트의 outbox 행이 같은 ID를 갖는다. */
class MdcPropagatingExecutorTests {

    @Test
    @DisplayName("제출한 스레드의 요청 ID가 실행 스레드에 보이고, 작업이 끝나면 지워진다")
    void propagatesAndClears() throws Exception {
        try (ExecutorService executor = new MdcPropagatingExecutor(
                Executors.newThreadPerTaskExecutor(Thread.ofVirtual().factory()))) {
            MDC.put(RequestIdContext.MDC_KEY, "trace-pg-1");
            CompletableFuture<String> inside = new CompletableFuture<>();
            executor.execute(() -> inside.complete(RequestIdContext.current()));
            assertThat(inside.get(5, TimeUnit.SECONDS)).isEqualTo("trace-pg-1");

            MDC.remove(RequestIdContext.MDC_KEY);
            CompletableFuture<String> none = new CompletableFuture<>();
            executor.submit(() -> none.complete(RequestIdContext.current())).get(5, TimeUnit.SECONDS);
            assertThat(none.get()).isNull();
        } finally {
            MDC.clear();
        }
    }
}
