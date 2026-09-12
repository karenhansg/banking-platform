package com.example.banking.account;

import com.example.banking.common.Money;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.springframework.test.util.ReflectionTestUtils;

/** Builds domain objects as if they had been loaded from the database. */
public final class AccountFixtures {

    private AccountFixtures() {
    }

    public static Account account(UUID id, String ownerName, String balance, Instant createdAt) {
        Account account = new Account(ownerName, createdAt);
        ReflectionTestUtils.setField(account, "id", id);
        BigDecimal amount = Money.of(balance);
        if (amount.signum() > 0) {
            account.credit(amount, createdAt);
        }
        return account;
    }

    /** A ledger entry for a posting whose effect is already reflected in the account's balance. */
    public static AccountTransaction transaction(Account account, Posting posting, Instant createdAt) {
        AccountTransaction transaction = AccountTransaction.of(account, posting, createdAt);
        ReflectionTestUtils.setField(transaction, "id", UUID.randomUUID());
        return transaction;
    }
}
