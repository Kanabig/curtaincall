package io.github.kanabig.curtaincall.queue.config;

import io.github.kanabig.curtaincall.common.web.trace.RequestIdContext;
import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.RecordInterceptor;

/**
 * 메시지의 X-Request-Id 헤더를 MDC에 넣어 컨슈머 로그가 같은 추적 ID를 찍게 한다. outbox 모듈의 같은 이름 인터셉터와 규칙이 같다(이 서비스는 outbox를 쓰지 않는다).
 * Boot가 기본 리스너 컨테이너 팩토리에 RecordInterceptor 빈 하나를 붙인다.
 */
@Configuration(proxyBeanMethods = false)
public class RequestIdRecordInterceptorConfig {

    @Bean
    RecordInterceptor<Object, Object> requestIdRecordInterceptor() {
        return new RecordInterceptor<>() {
            @Override
            public ConsumerRecord<Object, Object> intercept(
                    ConsumerRecord<Object, Object> record, Consumer<Object, Object> consumer) {
                Header header = record.headers().lastHeader(RequestIdContext.HEADER);
                String requestId = header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
                if (!RequestIdContext.isValid(requestId)) {
                    requestId = RequestIdContext.generate();
                }
                MDC.put(RequestIdContext.MDC_KEY, requestId);
                return record;
            }

            @Override
            public void afterRecord(ConsumerRecord<Object, Object> record, Consumer<Object, Object> consumer) {
                MDC.remove(RequestIdContext.MDC_KEY);
            }
        };
    }
}
