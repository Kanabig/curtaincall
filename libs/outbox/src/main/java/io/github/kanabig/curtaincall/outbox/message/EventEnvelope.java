package io.github.kanabig.curtaincall.outbox.message;

import com.fasterxml.jackson.databind.JsonNode;

/** 이벤트·명령 공통 봉투. occurredAt은 오프셋이 붙은 ISO-8601 문자열이다. */
public record EventEnvelope(
        String eventId, String eventType, String occurredAt, String aggregateId, JsonNode payload) {}
