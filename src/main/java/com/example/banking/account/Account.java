package com.example.banking.account;

import com.example.banking.common.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "accounts")
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "owner_name", nullable = false, length = 100)
    private String ownerName;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal balance;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected Account() {
        // for JPA
    }

    public Account(String ownerName, Instant now) {
        Objects.requireNonNull(ownerName, "ownerName must not be null");
        if (ownerName.isBlank()) {
            throw new IllegalArgumentException("ownerName must not be blank");
        }
        this.ownerName = ownerName.strip();
        this.balance = Money.ZERO;
        this.createdAt = now;
        this.updatedAt = now;
    }

    // Balance changes are package-private: only the Ledger may apply them, so none can skip the ledger entry.

    void credit(BigDecimal amount, Instant now) {
        balance = balance.add(Money.positive(amount));
        updatedAt = now;
    }

    void debit(BigDecimal amount, Instant now) {
        BigDecimal debit = Money.positive(amount);
        if (balance.compareTo(debit) < 0) {
            throw new InsufficientFundsException(balance, debit);
        }
        balance = balance.subtract(debit);
        updatedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public String getOwnerName() {
        return ownerName;
    }

    public BigDecimal getBalance() {
        return balance;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
