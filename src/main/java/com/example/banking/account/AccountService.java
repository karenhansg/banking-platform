package com.example.banking.account;

import com.example.banking.common.IdempotencyKeyReusedException;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class AccountService {

    private final AccountRepository accounts;
    private final AccountTransactionRepository transactions;
    private final Ledger ledger;
    private final DailyWithdrawalLimit dailyWithdrawalLimit;
    private final Clock clock;

    public AccountService(AccountRepository accounts, AccountTransactionRepository transactions, Ledger ledger,
            DailyWithdrawalLimit dailyWithdrawalLimit, Clock clock) {
        this.accounts = accounts;
        this.transactions = transactions;
        this.ledger = ledger;
        this.dailyWithdrawalLimit = dailyWithdrawalLimit;
        this.clock = clock;
    }

    @Transactional
    public Account openAccount(String ownerName) {
        return accounts.save(new Account(ownerName, clock.instant()));
    }

    public Account getAccount(UUID accountId) {
        return accounts.findById(accountId).orElseThrow(() -> new AccountNotFoundException(accountId));
    }

    public AccountBalance getBalance(UUID accountId) {
        Account account = getAccount(accountId);
        return new AccountBalance(
                account.getId(),
                account.getBalance(),
                dailyWithdrawalLimit.limit(),
                dailyWithdrawalLimit.remainingToday(accountId),
                clock.instant());
    }

    @Transactional
    public AccountTransaction deposit(UUID accountId, BigDecimal amount, @Nullable String description,
            @Nullable String idempotencyKey) {
        return postIdempotently(accountId, Posting.deposit(amount, description, idempotencyKey));
    }

    /**
     * @throws InsufficientFundsException            if the amount exceeds the balance
     * @throws DailyWithdrawalLimitExceededException if the amount exceeds what remains of today's limit
     */
    @Transactional
    public AccountTransaction withdraw(UUID accountId, BigDecimal amount, @Nullable String description,
            @Nullable String idempotencyKey) {
        return postIdempotently(accountId, Posting.withdrawal(amount, description, idempotencyKey));
    }

    public Page<AccountTransaction> getTransactions(UUID accountId, Pageable pageable) {
        if (!accounts.existsById(accountId)) {
            throw new AccountNotFoundException(accountId);
        }
        return transactions.findByAccountId(accountId, pageable);
    }

    private AccountTransaction postIdempotently(UUID accountId, Posting posting) {
        // Taking the lock first serialises retries of the same request, so the replay check cannot race.
        Account account = ledger.lockAccount(accountId);
        String idempotencyKey = posting.idempotencyKey();
        if (idempotencyKey != null) {
            Optional<AccountTransaction> previous = ledger.findByIdempotencyKey(accountId, idempotencyKey);
            if (previous.isPresent()) {
                if (!previous.get().isSameMovementAs(posting)) {
                    throw new IdempotencyKeyReusedException(idempotencyKey);
                }
                return previous.get();
            }
        }
        return ledger.post(account, posting);
    }
}
