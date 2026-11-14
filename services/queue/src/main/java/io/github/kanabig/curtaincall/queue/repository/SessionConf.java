package io.github.kanabig.curtaincall.queue.repository;

/** 회차 이벤트에서 뽑은 설정. {@code version}은 이벤트 발생 시각(epoch ms)이다. */
public record SessionConf(
        long sessionId, Type type, long version, long saleOpenAtMs, int admissionPerSecond, int maxActiveUsers) {

    public enum Type {
        UPSERT,
        SUSPEND,
        DELETE
    }
}
