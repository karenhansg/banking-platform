package com.example.banking.account;

import com.example.banking.common.Money;
import com.example.banking.config.BankingProperties;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The most an account may withdraw per banking day, counting withdrawals and executed scheduled payments. A banking
 * day runs from midnight to midnight in the bank's time zone. Deposits and refunds do not restore the allowance.
 */
@Component
public class DailyWithdrawalLimit {

    private static final Set<TransactionType> COUNTED_TYPES = TransactionType.countingTowardsDailyWithdrawalLimit();

    private final AccountTransactionRepository transactions;
    private final Clock clock;
    private final ZoneId businessTimeZone;
    private final BigDecimal limit;

    public DailyWithdrawalLimit(AccountTransactionRepository transactions, Clock clock, BankingProperties properties) {
        this.transactions = transactions;
        this.clock = clock;
        this.businessTimeZone = properties.businessTimeZone();
        this.limit = Money.of(properties.dailyWithdrawalLimit());
    }

    public BigDecimal limit() {
        return limit;
    }

    /** How much more the account may withdraw today; never negative. */
    public BigDecimal remainingToday(UUID accountId) {
        return limit.subtract(withdrawnToday(accountId)).max(Money.ZERO);
    }

    /**
     * Rejects an amount that would take today's withdrawals above the limit.
     *
     * <p>The caller must hold the account's row lock: otherwise two concurrent withdrawals could both pass this check.
     *
     * @throws DailyWithdrawalLimitExceededException if the amount exceeds what remains of today's limit
     */
    public void verify(UUID accountId, BigDecimal amount) {
        BigDecimal remaining = remainingToday(accountId);
        if (amount.compareTo(remaining) > 0) {
            throw new DailyWithdrawalLimitExceededException(limit, remaining, amount);
        }
    }

    /**
     * Rejects an amount that could never be withdrawn in a single banking day, whatever else happens that day.
     *
     * @throws DailyWithdrawalLimitExceededException if the amount exceeds the daily limit
     */
    public void verifyWithinLimit(BigDecimal amount) {
        if (amount.compareTo(limit) > 0) {
            throw new DailyWithdrawalLimitExceededException(limit, limit, amount);
        }
    }

    private BigDecimal withdrawnToday(UUID accountId) {
        LocalDate today = LocalDate.ofInstant(clock.instant(), businessTimeZone);
        Instant startOfDay = today.atStartOfDay(businessTimeZone).toInstant();
        Instant startOfNextDay = today.plusDays(1).atStartOfDay(businessTimeZone).toInstant();
        return transactions.sumAmounts(accountId, COUNTED_TYPES, startOfDay, startOfNextDay).orElse(Money.ZERO);
    }
}
