package com.example.banking.account;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * @param remainingDailyWithdrawalLimit how much more may be withdrawn (or paid out) before the limit resets at midnight
 */
public record AccountBalance(
        UUID accountId,
        BigDecimal balance,
        BigDecimal dailyWithdrawalLimit,
        BigDecimal remainingDailyWithdrawalLimit,
        Instant asOf) {
}
