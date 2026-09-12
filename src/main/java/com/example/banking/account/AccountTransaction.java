package com.example.banking.account;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;
import org.jspecify.annotations.Nullable;

/** An immutable ledger entry. The database role used by the application cannot update or delete these rows. */
@Entity
@Immutable
@Table(name = "account_transactions")
public class AccountTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private TransactionType type;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(name = "balance_after", nullable = false, precision = 19, scale = 2)
    private BigDecimal balanceAfter;

    @Column(length = 140)
    private @Nullable String description;

    @Column(name = "payment_id")
    private @Nullable UUID paymentId;

    @Column(name = "idempotency_key", length = 64)
    private @Nullable String idempotencyKey;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected AccountTransaction() {
        // for JPA
    }

    private AccountTransaction(Account account, Posting posting, Instant now) {
        this.accountId = account.getId();
        this.type = posting.type();
        this.amount = posting.amount();
        this.balanceAfter = account.getBalance();
        this.description = posting.description();
        this.paymentId = posting.paymentId();
        this.idempotencyKey = posting.idempotencyKey();
        this.createdAt = now;
    }

    /** Records a posting that has just been applied to the account's balance. */
    static AccountTransaction of(Account account, Posting posting, Instant now) {
        return new AccountTransaction(account, posting, now);
    }

    /** Whether a retried request with the same idempotency key describes the same money movement. */
    boolean isSameMovementAs(Posting posting) {
        return type == posting.type() && amount.compareTo(posting.amount()) == 0;
    }

    public UUID getId() {
        return id;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public TransactionType getType() {
        return type;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public BigDecimal getBalanceAfter() {
        return balanceAfter;
    }

    public @Nullable String getDescription() {
        return description;
    }

    public @Nullable UUID getPaymentId() {
        return paymentId;
    }

    public @Nullable String getIdempotencyKey() {
        return idempotencyKey;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
