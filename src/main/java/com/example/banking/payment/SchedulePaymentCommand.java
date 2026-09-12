package com.example.banking.payment;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * @param executeAt when to take the funds from the source account; {@code null} means immediately
 */
public record SchedulePaymentCommand(
        UUID sourceAccountId,
        UUID targetAccountId,
        BigDecimal amount,
        @Nullable Instant executeAt,
        @Nullable String description,
        @Nullable String idempotencyKey) {
}
