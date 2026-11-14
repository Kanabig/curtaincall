package io.github.kanabig.curtaincall.reservation.controller;

import io.github.kanabig.curtaincall.common.web.error.BusinessException;
import io.github.kanabig.curtaincall.common.web.error.CommonErrorCode;
import java.util.UUID;

final class IdempotencyKeys {

    private IdempotencyKeys() {}

    static String requireUuid(String key) {
        try {
            return UUID.fromString(key).toString();
        } catch (IllegalArgumentException e) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "Idempotency-Key는 UUID여야 한다");
        }
    }
}
