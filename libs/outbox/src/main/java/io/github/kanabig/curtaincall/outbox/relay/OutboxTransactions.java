package io.github.kanabig.curtaincall.outbox.relay;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

public final class OutboxTransactions {

    private OutboxTransactions() {}

    /**
     * outbox 테이블을 잠그는 작업은 READ COMMITTED로 실행한다. REPEATABLE READ에서는 {@code published_at} 인덱스 범위 스캔이 next-key
     * 락을 잡아, 새 미발행 행이 들어갈 자리가 잠기고 도메인 트랜잭션의 outbox INSERT가 기다린다.
     */
    public static TransactionTemplate readCommitted(PlatformTransactionManager transactionManager) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return template;
    }
}
