package io.github.kanabig.curtaincall.reservation.service;

import io.github.kanabig.curtaincall.common.web.error.BusinessException;
import io.github.kanabig.curtaincall.reservation.error.ReservationErrorCode;

/**
 * [QUE-5] gateway가 입장 토큰을 검증해 넘기는 {@code X-Entry-Session-Id}와 요청이 가리키는 회차를 맞춰 본다. 토큰 서명·만료는 gateway가 보고, 어느 회차의 토큰인지는 회차를
 * 아는 이쪽에서 본다. 헤더가 없으면 gateway를 거치지 않은 요청이므로 같은 이유로 거절한다.
 */
public final class EntrySession {

    private EntrySession() {}

    public static void require(Long entrySessionId, long requestSessionId) {
        if (entrySessionId == null || entrySessionId != requestSessionId) {
            throw new BusinessException(ReservationErrorCode.ENTRY_TOKEN_INVALID);
        }
    }
}
