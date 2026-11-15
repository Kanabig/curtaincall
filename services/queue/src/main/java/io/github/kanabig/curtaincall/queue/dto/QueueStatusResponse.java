package io.github.kanabig.curtaincall.queue.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 대기 상태 응답. {@code WAITING}이면 순번 필드, {@code ADMITTED}면 {@code expiresAt}, {@code EXPIRED}면 상태만 채운다. {@code ADMITTED}에는 입장 토큰이
 * 붙는다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record QueueStatusResponse(
        String status,
        Long position,
        Long ahead,
        Long etaSeconds,
        Long pollAfterMs,
        String entryToken,
        String expiresAt) {

    public static QueueStatusResponse waiting(long ahead, long etaSeconds, long pollAfterMs) {
        return new QueueStatusResponse("WAITING", ahead + 1, ahead, etaSeconds, pollAfterMs, null, null);
    }

    /** 매진 중 대기. 순번은 유지하고 입장 예상 시간은 알 수 없어 주지 않는다. */
    public static QueueStatusResponse soldOut(long ahead, long pollAfterMs) {
        return new QueueStatusResponse("SOLD_OUT", ahead + 1, ahead, null, pollAfterMs, null, null);
    }

    public static QueueStatusResponse admitted(String entryToken, String expiresAt) {
        return new QueueStatusResponse("ADMITTED", null, null, null, null, entryToken, expiresAt);
    }

    public static QueueStatusResponse expired() {
        return new QueueStatusResponse("EXPIRED", null, null, null, null, null, null);
    }
}
