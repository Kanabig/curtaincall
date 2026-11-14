package io.github.kanabig.curtaincall.reservation.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kanabig.curtaincall.reservation.repository.IdempotencyRepository;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Idempotency-Key로 요청을 한 번만 처리한다. 같은 키의 요청은 키 행의 FOR UPDATE에서 줄을 서고, 앞선 요청이 커밋하면 저장된 첫 응답을 돌려준다. 처리가
 * 실패해 롤백되면 응답이 저장되지 않으므로 같은 키로 다시 시도할 수 있다.
 */
@Component
class IdempotentRequests {

    private final IdempotencyRepository repository;
    private final TransactionTemplate tx;
    private final ObjectMapper objectMapper;

    IdempotentRequests(IdempotencyRepository repository, TransactionTemplate tx, ObjectMapper objectMapper) {
        this.repository = repository;
        this.tx = tx;
        this.objectMapper = objectMapper;
    }

    <T> T execute(long userId, String scope, String key, Class<T> type, Supplier<T> action) {
        repository.ensure(userId, scope, key);
        return tx.execute(status -> {
            String saved = repository.lockResponse(userId, scope, key);
            if (saved != null) {
                return read(saved, type);
            }
            T result = action.get();
            repository.saveResponse(userId, scope, key, write(result));
            return result;
        });
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private <T> T read(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("저장된 응답을 읽을 수 없다", e);
        }
    }
}
