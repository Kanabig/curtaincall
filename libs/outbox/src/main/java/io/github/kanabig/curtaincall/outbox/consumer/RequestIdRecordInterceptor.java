package io.github.kanabig.curtaincall.outbox.consumer;

import io.github.kanabig.curtaincall.common.web.trace.RequestIdContext;
import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.MDC;
import org.springframework.kafka.listener.RecordInterceptor;

/**
 * 메시지의 X-Request-Id 헤더를 읽어 리스너와 에러 핸들러가 도는 동안 MDC에 넣는다. 레코드 하나가 끝나면 지운다.
 *
 * <p>헤더가 없거나 형식이 맞지 않으면 새 ID를 만든다. 그래야 이 메시지를 처리하다 다시 발행하는 이벤트도 추적 ID를 갖는다.
 */
public class RequestIdRecordInterceptor implements RecordInterceptor<String, String> {

    @Override
    public ConsumerRecord<String, String> intercept(
            ConsumerRecord<String, String> record, Consumer<String, String> consumer) {
        Header header = record.headers().lastHeader(RequestIdContext.HEADER);
        String requestId = header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
        if (!RequestIdContext.isValid(requestId)) {
            requestId = RequestIdContext.generate();
        }
        MDC.put(RequestIdContext.MDC_KEY, requestId);
        return record;
    }

    @Override
    public void afterRecord(ConsumerRecord<String, String> record, Consumer<String, String> consumer) {
        MDC.remove(RequestIdContext.MDC_KEY);
    }
}
