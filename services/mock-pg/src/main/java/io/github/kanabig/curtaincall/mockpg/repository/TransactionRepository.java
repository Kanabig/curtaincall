package io.github.kanabig.curtaincall.mockpg.repository;

import io.github.kanabig.curtaincall.mockpg.entity.Transaction;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Repository;

@Repository
public class TransactionRepository {

    private final Map<String, Transaction> byMerchantOrderId = new ConcurrentHashMap<>();
    private final Map<String, Transaction> byTid = new ConcurrentHashMap<>();

    /** 같은 가맹점 주문번호가 이미 있으면 먼저 저장된 거래를 돌려주고, 새로 저장했으면 비어 있다. */
    public Optional<Transaction> insertIfAbsent(Transaction transaction) {
        Transaction existing = byMerchantOrderId.putIfAbsent(transaction.getMerchantOrderId(), transaction);
        if (existing != null) {
            return Optional.of(existing);
        }
        byTid.put(transaction.getTid(), transaction);
        return Optional.empty();
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
    }
}
