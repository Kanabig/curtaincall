package io.github.kanabig.curtaincall.event.entity;

/** 공연 상세 조회 상태. PENDING은 새로 보이거나 바뀐 공연, FAILED는 파싱 실패가 반복돼 다음 변경 때까지 쉬는 공연이다. */
public enum DetailStatus {
    PENDING,
    DONE,
    FAILED
}
