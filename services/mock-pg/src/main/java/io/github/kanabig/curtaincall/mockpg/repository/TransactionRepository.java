package io.github.kanabig.curtaincall.mockpg.repository;

import io.github.kanabig.curtaincall.mockpg.entity.Transaction;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Repository;

@Repository
public class TransactionRepository {

    /** 사전 취소된 주문번호로 승인이 들어왔다. */
    public static class OrderPreCancelledException extends RuntimeException {
        OrderPreCancelledException(String merchantOrderId) {
            super("order was cancelled before approval: " + merchantOrderId);
        }
    }

    private final Map<String, Transaction> byMerchantOrderId = new ConcurrentHashMap<>();
    private final Map<String, Transaction> byTid = new ConcurrentHashMap<>();
    private final Set<String> preCancelled = ConcurrentHashMap.newKeySet();

    /** 같은 가맹점 주문번호가 이미 있으면 먼저 저장된 거래를 돌려주고, 새로 저장했으면 비어 있다. */
    public synchronized Optional<Transaction> insertIfAbsent(Transaction transaction) {
        if (preCancelled.contains(transaction.getMerchantOrderId())) {
            throw new OrderPreCancelledException(transaction.getMerchantOrderId());
        }
        Transaction existing = byMerchantOrderId.putIfAbsent(transaction.getMerchantOrderId(), transaction);
        if (existing != null) {
            return Optional.of(existing);
        }
        byTid.put(transaction.getTid(), transaction);
        return Optional.empty();
    }

    /**
     * 거래가 없는 가맹점 주문번호의 취소를 기록한다(사전 취소). 승인 삽입과 같은 락 안에서 판정하므로, 취소가 먼저 기록되면 뒤따르는 승인은 반드시 거절되고
     * 승인이 먼저 들어갔으면 사전 취소는 기록되지 않는다. 이미 거래가 있으면 그 거래를 돌려준다.
     */
    public synchronized Optional<Transaction> preCancelIfAbsent(String merchantOrderId) {
        Transaction existing = byMerchantOrderId.get(merchantOrderId);
        if (existing != null) {
            return Optional.of(existing);
        }
        preCancelled.add(merchantOrderId);
        return Optional.empty();
    }

    public synchronized boolean isPreCancelled(String merchantOrderId) {
        return preCancelled.contains(merchantOrderId);
    }

    public Optional<Transaction> findByMerchantOrderId(String merchantOrderId) {
        return Optional.ofNullable(byMerchantOrderId.get(merchantOrderId));
    }

    public Optional<Transaction> findByTid(String tid) {
        return Optional.ofNullable(byTid.get(tid));
    }

    public void clear() {
        byMerchantOrderId.clear();
        byTid.clear();
        preCancelled.clear();
    }
}
