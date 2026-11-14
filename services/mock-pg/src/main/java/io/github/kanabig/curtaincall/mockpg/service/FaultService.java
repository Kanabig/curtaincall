package io.github.kanabig.curtaincall.mockpg.service;

import io.github.kanabig.curtaincall.mockpg.config.MockPgConfig;
import io.github.kanabig.curtaincall.mockpg.dto.FaultSettings;
import java.util.concurrent.atomic.AtomicReference;
import java.util.random.RandomGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** 런타임에 바꿀 수 있는 장애 주입 상태. 승인 요청에만 적용하고 조회·취소는 항상 정상 응답한다. */
@Service
public class FaultService {

    private static final Logger log = LoggerFactory.getLogger(FaultService.class);

    private final AtomicReference<FaultSettings> settings = new AtomicReference<>(FaultSettings.NONE);
    private final RandomGenerator random;
    private final long timeoutHangMs;

    public FaultService(RandomGenerator random, MockPgConfig.Properties properties) {
        this.random = random;
        this.timeoutHangMs = properties.timeoutHangMs();
    }

    public FaultSettings get() {
        return settings.get();
    }

    public FaultSettings replace(FaultSettings next) {
        settings.set(next);
        log.info("장애 주입 설정 변경: {}", next);
        return next;
    }

    public FaultSettings reset() {
        return replace(FaultSettings.NONE);
    }

    /** 승인 처리 전에 적용하는 장애: 지연 → 타임아웃(응답 없이 대기) → 실패. 이 단계에서 걸리면 거래는 기록되지 않는다. */
    public void beforeApproval() {
        FaultSettings current = settings.get();
        if (current.delayMs() > 0) {
            sleep(current.delayMs());
        }
        if (roll(current.timeoutRate())) {
            sleep(timeoutHangMs);
            throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "injected timeout");
        }
        if (roll(current.failureRate())) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "injected failure");
        }
    }

    /** 승인을 처리하고 기록한 뒤 호출한다. true면 응답을 보내지 않고 연결을 끊는다. */
    public boolean shouldDropResponse() {
        return roll(settings.get().dropResponseRate());
    }

    private boolean roll(double rate) {
        return rate > 0 && random.nextDouble() < rate;
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "interrupted");
        }
    }
}
