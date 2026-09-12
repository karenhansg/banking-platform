package com.example.banking.payment.api;

import com.example.banking.payment.PaymentStatus;
import com.example.banking.payment.ScheduledPayment;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public record PaymentResponse(
        UUID id,
        UUID sourceAccountId,
        UUID targetAccountId,
        BigDecimal amount,
        @Nullable String description,
        PaymentStatus status,
        Instant executeAt,
        @Nullable Instant heldAt,
        @Nullable Instant expiresAt,
        @Nullable Instant resolvedAt,
        @Nullable String failureReason,
        Instant createdAt) {

    static PaymentResponse from(ScheduledPayment payment) {
        return new PaymentResponse(
                payment.getId(),
                payment.getSourceAccountId(),
                payment.getTargetAccountId(),
                payment.getAmount(),
                payment.getDescription(),
                payment.getStatus(),
                payment.getExecuteAt(),
                payment.getHeldAt(),
                payment.getExpiresAt(),
                payment.getResolvedAt(),
                payment.getFailureReason(),
                payment.getCreatedAt());
    }
}
