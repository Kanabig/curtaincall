package io.github.kanabig.curtaincall.event.entity;

/** RUNNING은 진행 중, PARTIAL은 호출 상한에 닿아 남은 대상을 다음 주기로 넘긴 실행, FAILED는 KOPIS 호출 실패로 끝낸 실행이다. */
public enum SyncStatus {
    RUNNING,
    SUCCESS,
    PARTIAL,
    FAILED,
    SKIPPED
}
