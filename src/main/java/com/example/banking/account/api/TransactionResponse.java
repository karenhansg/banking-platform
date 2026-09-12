package com.example.banking.account.api;

import com.example.banking.account.AccountTransaction;
import com.example.banking.account.TransactionType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public record TransactionResponse(
        UUID id,
        UUID accountId,
        TransactionType type,
        BigDecimal amount,
        BigDecimal balanceAfter,
        @Nullable String description,
        @Nullable UUID paymentId,
        Instant createdAt) {

    static TransactionResponse from(AccountTransaction transaction) {
        return new TransactionResponse(
                transaction.getId(),
                transaction.getAccountId(),
                transaction.getType(),
                transaction.getAmount(),
                transaction.getBalanceAfter(),
                transaction.getDescription(),
                transaction.getPaymentId(),
                transaction.getCreatedAt());
    }
}
