package com.example.banking.payment;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface ScheduledPaymentRepository extends JpaRepository<ScheduledPayment, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from ScheduledPayment p where p.id = :id")
    Optional<ScheduledPayment> findByIdForUpdate(UUID id);

    Optional<ScheduledPayment> findBySourceAccountIdAndIdempotencyKey(UUID sourceAccountId, String idempotencyKey);

    /** Payments sent or received by the account, optionally filtered by status. */
    @Query("""
            select p from ScheduledPayment p
            where (p.sourceAccountId = :accountId or p.targetAccountId = :accountId)
              and (:status is null or p.status = :status)
            """)
    Page<ScheduledPayment> findByAccount(UUID accountId, @Nullable PaymentStatus status, Pageable pageable);

    // The claim queries lock the returned row and SKIP LOCKED rows other transactions are already processing, so any
    // number of application instances can run the payment scheduler without coordination or double processing.

    @Query(value = """
            SELECT * FROM scheduled_payments
            WHERE status = 'SCHEDULED' AND execute_at <= :now
            ORDER BY execute_at
            LIMIT 1
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    Optional<ScheduledPayment> claimNextDuePayment(Instant now);

    @Query(value = """
            SELECT * FROM scheduled_payments
            WHERE status = 'PENDING_ACCEPTANCE' AND expires_at <= :now
            ORDER BY expires_at
            LIMIT 1
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    Optional<ScheduledPayment> claimNextOverduePayment(Instant now);
}
