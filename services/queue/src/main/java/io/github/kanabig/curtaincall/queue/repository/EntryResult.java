package io.github.kanabig.curtaincall.queue.repository;

/** enter·status 스크립트의 결과. 값이 없는 필드는 0이다. */
public record EntryResult(Kind kind, long rank, long admissionPerSecond, long epochMs, boolean created) {

    public enum Kind {
        WAITING,
        ADMITTED,
        NOT_IN_QUEUE,
        SESSION_NOT_FOUND,
        NOT_OPENED,
        NOT_ON_SALE
    }

    static EntryResult of(Kind kind) {
        return new EntryResult(kind, 0, 0, 0, false);
    }
}
