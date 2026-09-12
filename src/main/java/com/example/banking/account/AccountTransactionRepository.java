package com.example.banking.account;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface AccountTransactionRepository extends JpaRepository<AccountTransaction, UUID> {

    /** Total amount of the account's entries of the given types created in {@code [from, to)}, if there are any. */
    @Query("""
            select sum(t.amount) from AccountTransaction t
            where t.accountId = :accountId
              and t.type in :types
              and t.createdAt >= :from and t.createdAt < :to
            """)
    Optional<BigDecimal> sumAmounts(UUID accountId, Collection<TransactionType> types, Instant from, Instant to);

    Optional<AccountTransaction> findByAccountIdAndIdempotencyKey(UUID accountId, String idempotencyKey);

    Page<AccountTransaction> findByAccountId(UUID accountId, Pageable pageable);
}
