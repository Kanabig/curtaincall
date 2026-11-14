package io.github.kanabig.curtaincall.queue.service;

import io.github.kanabig.curtaincall.common.web.error.BusinessException;
import io.github.kanabig.curtaincall.queue.config.QueueProperties;
import io.github.kanabig.curtaincall.queue.dto.QueueStatusResponse;
import io.github.kanabig.curtaincall.queue.error.QueueErrorCode;
import io.github.kanabig.curtaincall.queue.repository.EntryResult;
import io.github.kanabig.curtaincall.queue.repository.QueueRepository;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/** [QUE-1~3, QUE-5] 대기열 진입과 순번 조회, 입장하면 입장 토큰을 붙인다. Redis가 응답하지 않으면 503으로 돌려 진입만 멈춘다(NFR-11). */
@Service
public class QueueService {

    private static final Logger log = LoggerFactory.getLogger(QueueService.class);

    private final QueueRepository repository;
    private final PollPolicy pollPolicy =
            new PollPolicy(() -> ThreadLocalRandom.current().nextDouble());
    private final ZoneId zone;
    private final EntryTokenIssuer tokenIssuer;

    public QueueService(QueueRepository repository, QueueProperties properties, EntryTokenIssuer tokenIssuer) {
        this.repository = repository;
        this.tokenIssuer = tokenIssuer;
        this.zone = properties.zone();
    }

    /** 이미 대기 중이면 기존 순번을 그대로 돌려준다. {@link Entered#created()}는 이번 호출로 새로 들어왔는지다. */
    public Entered enter(long sessionId, long userId) {
        EntryResult result = call(() -> repository.enter(sessionId, userId));
        return new Entered(toResponse(result, sessionId, userId), result.created());
    }

    public QueueStatusResponse status(long sessionId, long userId) {
        return toResponse(call(() -> repository.status(sessionId, userId)), sessionId, userId);
    }

    /** 대기 포기. 대기 중이 아니어도 오류 없이 끝난다. */
    public void leave(long sessionId, long userId) {
        call(() -> {
            repository.leave(sessionId, userId);
            return null;
        });
    }

    private QueueStatusResponse toResponse(EntryResult r, long sessionId, long userId) {
        return switch (r.kind()) {
            case WAITING ->
                QueueStatusResponse.waiting(
                        r.rank(),
                        pollPolicy.etaSeconds(r.rank(), r.admissionPerSecond()),
                        pollPolicy.pollAfterMs(r.rank(), r.admissionPerSecond()));
            case ADMITTED ->
                QueueStatusResponse.admitted(
                        tokenIssuer.issue(userId, sessionId, Instant.ofEpochMilli(r.epochMs())), format(r.epochMs()));
            case NOT_IN_QUEUE -> QueueStatusResponse.expired();
            case SESSION_NOT_FOUND -> throw new BusinessException(QueueErrorCode.SESSION_NOT_FOUND);
            case NOT_ON_SALE -> throw new BusinessException(QueueErrorCode.SESSION_NOT_ON_SALE);
            case NOT_OPENED ->
                throw new BusinessException(QueueErrorCode.SALE_NOT_OPENED).with("opensAt", format(r.epochMs()));
        };
    }

    private String format(long epochMs) {
        return OffsetDateTime.ofInstant(Instant.ofEpochMilli(epochMs), zone).toString();
    }

    private static <T> T call(java.util.function.Supplier<T> action) {
        try {
            return action.get();
        } catch (DataAccessException e) {
            log.warn("Redis 호출 실패로 대기열을 사용할 수 없다: {}", e.toString());
            throw new BusinessException(QueueErrorCode.QUEUE_UNAVAILABLE);
        }
    }

    public record Entered(QueueStatusResponse response, boolean created) {}
}
