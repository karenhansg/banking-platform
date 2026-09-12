package com.example.banking.account;

import com.example.banking.common.BusinessException;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only way to change an account balance. Each posting updates the balance and appends an immutable ledger entry
 * within the caller's transaction.
 *
 * <p>Callers lock the account with {@link #lockAccount} before posting. The row lock serialises every balance change
 * to an account, which is what keeps the funds check and the daily-limit check correct under concurrent requests.
 *
 * <p>Business rule violations do not mark the caller's transaction rollback-only, so a caller may catch them and
 * record the outcome (for example, a scheduled payment that fails at execution time).
 */
@Component
@Transactional(propagation = Propagation.MANDATORY, noRollbackFor = BusinessException.class)
public class Ledger {

    private static final Logger audit = LoggerFactory.getLogger("banking.audit");

    private final AccountRepository accounts;
    private final AccountTransactionRepository transactions;
    private final DailyWithdrawalLimit dailyWithdrawalLimit;
    private final Clock clock;

    public Ledger(AccountRepository accounts, AccountTransactionRepository transactions,
            DailyWithdrawalLimit dailyWithdrawalLimit, Clock clock) {
        this.accounts = accounts;
        this.transactions = transactions;
        this.dailyWithdrawalLimit = dailyWithdrawalLimit;
        this.clock = clock;
    }

    /**
     * Loads the account and locks it until the current transaction ends.
     *
     * @throws AccountNotFoundException if there is no such account
     */
    public Account lockAccount(UUID accountId) {
        return accounts.findByIdForUpdate(accountId).orElseThrow(() -> new AccountNotFoundException(accountId));
    }

    public Optional<AccountTransaction> findByIdempotencyKey(UUID accountId, String idempotencyKey) {
        return transactions.findByAccountIdAndIdempotencyKey(accountId, idempotencyKey);
    }

    /**
     * Applies a posting to an account locked by {@link #lockAccount}.
     *
     * @throws InsufficientFundsException            if a debit exceeds the balance
     * @throws DailyWithdrawalLimitExceededException if a withdrawal or payment would exceed today's limit
     */
    public AccountTransaction post(Account account, Posting posting) {
        Instant now = clock.instant();
        TransactionType type = posting.type();
        if (type.isDebit()) {
            if (type.countsTowardsDailyWithdrawalLimit()) {
                dailyWithdrawalLimit.verify(account.getId(), posting.amount());
            }
            account.debit(posting.amount(), now);
        } else {
            account.credit(posting.amount(), now);
        }
        AccountTransaction entry = transactions.save(AccountTransaction.of(account, posting, now));
        audit(entry);
        return entry;
    }

    private static void audit(AccountTransaction entry) {
        LoggingEventBuilder event = audit.atInfo()
                .addKeyValue("event", "ledger.posting")
                .addKeyValue("transactionId", entry.getId())
                .addKeyValue("accountId", entry.getAccountId())
                .addKeyValue("type", entry.getType())
                .addKeyValue("amount", entry.getAmount());
        if (entry.getPaymentId() != null) {
            event = event.addKeyValue("paymentId", entry.getPaymentId());
        }
        event.log("Posted {} of {} to account {}", entry.getType(), entry.getAmount(), entry.getAccountId());
    }
}
