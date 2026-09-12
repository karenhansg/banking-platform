package com.example.banking.payment.api;

import com.example.banking.payment.SchedulePaymentCommand;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * @param executeAt when to take the funds from the source account (ISO-8601 instant); omit to pay immediately
 */
public record SchedulePaymentRequest(
        @NotNull UUID sourceAccountId,
        @NotNull UUID targetAccountId,
        @NotNull @DecimalMin("0.01") @Digits(integer = 13, fraction = 2) BigDecimal amount,
        @Nullable Instant executeAt,
        @Size(max = 140) @Nullable String description) {

    SchedulePaymentCommand toCommand(@Nullable String idempotencyKey) {
        return new SchedulePaymentCommand(sourceAccountId, targetAccountId, amount, executeAt, description,
                idempotencyKey);
    }
}
