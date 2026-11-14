package io.github.kanabig.curtaincall.common.web.trace;

import java.util.UUID;
import java.util.regex.Pattern;
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

    // 로그 위조(개행 삽입 등)를 막으려고 허용 문자와 길이를 제한한다
    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    private RequestIdContext() {}

    /** 로그에 안전하게 남길 수 있는 형식인지. null이면 false. */
    public static boolean isValid(String requestId) {
        return requestId != null && VALID.matcher(requestId).matches();
    }

    /** 새 추적 ID. */
    public static String generate() {
        return UUID.randomUUID().toString();
    }

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
