package com.example.banking.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.banking.common.Money;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LedgerTest {

    private static final Instant NOW = Instant.parse("2026-03-10T14:00:00Z");
    private static final UUID PAYMENT_ID = UUID.randomUUID();

    @Mock
    private AccountRepository accounts;

    @Mock
    private AccountTransactionRepository transactions;

    @Mock
    private DailyWithdrawalLimit dailyWithdrawalLimit;

    private Ledger ledger;

    @BeforeEach
    void setUp() {
        ledger = new Ledger(accounts, transactions, dailyWithdrawalLimit, Clock.fixed(NOW, ZoneOffset.UTC));
        lenient().when(transactions.save(any(AccountTransaction.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void depositCreditsTheAccountAndRecordsALedgerEntry() {
        Account account = accountWithBalance("100.00");

        AccountTransaction entry = ledger.post(account, Posting.deposit(Money.of("250.00"), "Salary", "salary-2026-03"));

        assertThat(account.getBalance()).isEqualTo(Money.of("350.00"));
        assertThat(entry.getAccountId()).isEqualTo(account.getId());
        assertThat(entry.getType()).isEqualTo(TransactionType.DEPOSIT);
        assertThat(entry.getAmount()).isEqualTo(Money.of("250.00"));
        assertThat(entry.getBalanceAfter()).isEqualTo(Money.of("350.00"));
        assertThat(entry.getDescription()).isEqualTo("Salary");
        assertThat(entry.getIdempotencyKey()).isEqualTo("salary-2026-03");
        assertThat(entry.getCreatedAt()).isEqualTo(NOW);
        verify(transactions).save(entry);
        verifyNoInteractions(dailyWithdrawalLimit);
    }

    @Test
    void withdrawalIsCheckedAgainstTheDailyLimitAndThenDebited() {
        Account account = accountWithBalance("500.00");

        AccountTransaction entry = ledger.post(account, Posting.withdrawal(Money.of("200.00"), null, null));

        verify(dailyWithdrawalLimit).verify(account.getId(), Money.of("200.00"));
        assertThat(account.getBalance()).isEqualTo(Money.of("300.00"));
        assertThat(entry.getBalanceAfter()).isEqualTo(Money.of("300.00"));
    }

    @Test
    void withdrawalOverTheDailyLimitLeavesTheAccountAndLedgerUntouched() {
        Account account = accountWithBalance("5000.00");
        doThrow(new DailyWithdrawalLimitExceededException(Money.of("1000.00"), Money.of("300.00"), Money.of("400.00")))
                .when(dailyWithdrawalLimit).verify(account.getId(), Money.of("400.00"));

        assertThatThrownBy(() -> ledger.post(account, Posting.withdrawal(Money.of("400.00"), null, null)))
                .isInstanceOf(DailyWithdrawalLimitExceededException.class);

        assertThat(account.getBalance()).isEqualTo(Money.of("5000.00"));
        verify(transactions, never()).save(any());
    }

    @Test
    void withdrawalBeyondTheBalanceIsRejected() {
        Account account = accountWithBalance("50.00");

        assertThatThrownBy(() -> ledger.post(account, Posting.withdrawal(Money.of("50.01"), null, null)))
                .isInstanceOf(InsufficientFundsException.class);

        verify(transactions, never()).save(any());
    }

    @Test
    void scheduledPaymentHoldCountsTowardsTheDailyLimit() {
        Account account = accountWithBalance("500.00");

        AccountTransaction entry = ledger.post(account, Posting.paymentHold(Money.of("100.00"), PAYMENT_ID));

        verify(dailyWithdrawalLimit).verify(account.getId(), Money.of("100.00"));
        assertThat(entry.getPaymentId()).isEqualTo(PAYMENT_ID);
        assertThat(account.getBalance()).isEqualTo(Money.of("400.00"));
    }

    @ParameterizedTest
    @EnumSource(value = TransactionType.class, names = {"PAYMENT_REFUND", "PAYMENT_RECEIVED"})
    void paymentCreditsDoNotInvolveTheDailyLimit(TransactionType type) {
        Account account = accountWithBalance("0.00");

        ledger.post(account, new Posting(type, Money.of("100.00"), null, PAYMENT_ID, null));

        assertThat(account.getBalance()).isEqualTo(Money.of("100.00"));
        verifyNoInteractions(dailyWithdrawalLimit);
    }

    @Test
    void lockingAnUnknownAccountFails() {
        UUID accountId = UUID.randomUUID();
        when(accounts.findByIdForUpdate(accountId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> ledger.lockAccount(accountId))
                .isInstanceOf(AccountNotFoundException.class)
                .hasMessageContaining(accountId.toString());
    }

    private static Account accountWithBalance(String balance) {
        return AccountFixtures.account(UUID.randomUUID(), "Ada Lovelace", balance, NOW.minusSeconds(3600));
    }
}
