package com.example.banking.account;

import com.example.banking.common.Money;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** A movement of money into or out of one account, applied by {@link Ledger#post}. */
public record Posting(
        TransactionType type,
        BigDecimal amount,
        @Nullable String description,
        @Nullable UUID paymentId,
        @Nullable String idempotencyKey) {

    public Posting {
        Objects.requireNonNull(type, "type must not be null");
        amount = Money.positive(amount);
    }

    public static Posting deposit(BigDecimal amount, @Nullable String description, @Nullable String idempotencyKey) {
        return new Posting(TransactionType.DEPOSIT, amount, description, null, idempotencyKey);
    }

    public static Posting withdrawal(BigDecimal amount, @Nullable String description,
            @Nullable String idempotencyKey) {
        return new Posting(TransactionType.WITHDRAWAL, amount, description, null, idempotencyKey);
    }

    public static Posting paymentHold(BigDecimal amount, UUID paymentId) {
        return forPayment(TransactionType.PAYMENT_HOLD, amount, paymentId);
    }

    public static Posting paymentRefund(BigDecimal amount, UUID paymentId) {
        return forPayment(TransactionType.PAYMENT_REFUND, amount, paymentId);
    }

    public static Posting paymentReceived(BigDecimal amount, UUID paymentId) {
        return forPayment(TransactionType.PAYMENT_RECEIVED, amount, paymentId);
    }

    private static Posting forPayment(TransactionType type, BigDecimal amount, UUID paymentId) {
        return new Posting(type, amount, null, Objects.requireNonNull(paymentId, "paymentId must not be null"), null);
    }
}
