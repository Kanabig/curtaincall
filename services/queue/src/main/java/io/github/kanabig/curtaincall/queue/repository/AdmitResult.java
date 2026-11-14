package io.github.kanabig.curtaincall.queue.repository;

/**
 * 입장 처리 한 번의 결과.
 *
 * @param ran 이번 초의 입장 처리를 이 호출이 맡았는지. 다른 인스턴스가 먼저 잡았거나 판매 중이 아니면 false
 * @param admitted 이번에 입장시킨 수
 * @param active 처리 뒤 활성 사용자 수
 */
public record AdmitResult(boolean ran, long admitted, long active) {

    static final AdmitResult SKIPPED = new AdmitResult(false, 0, 0);
}
