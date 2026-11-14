package io.github.kanabig.curtaincall.payment.repository;

import io.github.kanabig.curtaincall.payment.entity.Payment;
import io.github.kanabig.curtaincall.payment.entity.PaymentStatus;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentRepository extends JpaRepository<Payment, String> {

    Optional<Payment> findByOrderId(String orderId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.paymentId = :paymentId")
    Optional<Payment> findForUpdate(@Param("paymentId") String paymentId);

    /** 조회 시각이 된 UNKNOWN. 다른 인스턴스가 잡은 행은 건너뛴다(SKIP LOCKED). */
    @Query(value = """
                    SELECT * FROM payment
                    WHERE status = 'UNKNOWN' AND next_inquiry_at IS NOT NULL AND next_inquiry_at <= :now
                    ORDER BY next_inquiry_at
                    LIMIT :limit
                    FOR UPDATE SKIP LOCKED
                    """, nativeQuery = true)
    List<Payment> claimDueInquiries(@Param("now") Instant now, @Param("limit") int limit);

    /** 승인 호출 도중 멈춘 것으로 보이는 REQUESTED. */
    @Query(value = """
                    SELECT * FROM payment
                    WHERE status = 'REQUESTED' AND created_at <= :before
                    ORDER BY created_at
                    LIMIT :limit
                    FOR UPDATE SKIP LOCKED
                    """, nativeQuery = true)
    List<Payment> claimStaleRequests(@Param("before") Instant before, @Param("limit") int limit);

    long countByStatus(PaymentStatus status);
}
