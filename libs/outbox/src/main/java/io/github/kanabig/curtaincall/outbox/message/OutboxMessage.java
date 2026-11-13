package io.github.kanabig.curtaincall.outbox.message;

/** outbox 한 행. payload는 봉투 전체 JSON이다. */
public record OutboxMessage(
        long id, String topic, String key, String eventId, String eventType, String payload, String requestId) {}
