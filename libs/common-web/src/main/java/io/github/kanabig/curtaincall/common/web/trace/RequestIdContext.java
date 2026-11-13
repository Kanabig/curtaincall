package io.github.kanabig.curtaincall.common.web.trace;

import org.slf4j.MDC;

/**
 * 추적 ID(X-Request-Id)를 읽고 쓰는 공통 진입점.
 *
 * <p>HTTP와 Kafka 메시지 헤더가 같은 이름을 쓴다. 요청 스레드가 아닌 곳(Outbox 릴레이, 컨슈머)에서는 MDC가 비어 있으므로 ID를 메시지에 실어 보내고, 받는 쪽이
 * {@link #open(String)}으로 MDC에 다시 넣는다.
 */
public final class RequestIdContext {

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_KEY = "requestId";

    private RequestIdContext() {}

    /** 현재 스레드의 추적 ID. 없으면 null. */
    public static String current() {
        return MDC.get(MDC_KEY);
    }

    /** 추적 ID를 MDC에 넣고, close()에서 원래 값으로 되돌린다. try-with-resources로 쓴다. */
    public static Scope open(String requestId) {
        String previous = MDC.get(MDC_KEY);
        MDC.put(MDC_KEY, requestId);
        return () -> {
            if (previous == null) {
                MDC.remove(MDC_KEY);
            } else {
                MDC.put(MDC_KEY, previous);
            }
        };
    }

    /** close()가 예외를 던지지 않는 AutoCloseable. */
    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}
