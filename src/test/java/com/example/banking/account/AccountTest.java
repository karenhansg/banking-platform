package com.example.banking.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.banking.common.Money;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AccountTest {

    private static final Instant OPENED_AT = Instant.parse("2026-03-10T14:00:00Z");
    private static final Instant LATER = OPENED_AT.plusSeconds(60);

    @Test
    void opensWithAZeroBalance() {
        Account account = new Account("  Ada Lovelace ", OPENED_AT);

        assertThat(account.getBalance()).isEqualTo(Money.of("0.00"));
        assertThat(account.getOwnerName()).isEqualTo("Ada Lovelace");
        assertThat(account.getCreatedAt()).isEqualTo(OPENED_AT);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void requiresAnOwnerName(String ownerName) {
        assertThatIllegalArgumentException().isThrownBy(() -> new Account(ownerName, OPENED_AT));
    }

    @Test
    void creditIncreasesTheBalance() {
        Account account = new Account("Ada Lovelace", OPENED_AT);

        account.credit(Money.of("100.25"), LATER);
        account.credit(Money.of("0.75"), LATER);

        assertThat(account.getBalance()).isEqualTo(Money.of("101.00"));
        assertThat(account.getUpdatedAt()).isEqualTo(LATER);
    }

    @Test
    void debitDecreasesTheBalanceDownToZero() {
        Account account = accountWithBalance("100.00");

        account.debit(Money.of("60.00"), LATER);
        account.debit(Money.of("40.00"), LATER);

        assertThat(account.getBalance()).isEqualTo(Money.of("0.00"));
    }

    @Test
    void debitBeyondTheBalanceIsRejectedAndChangesNothing() {
        Account account = accountWithBalance("100.00");

        assertThatThrownBy(() -> account.debit(Money.of("100.01"), LATER))
                .isInstanceOfSatisfying(InsufficientFundsException.class, e -> assertThat(e.details())
                        .containsEntry("availableBalance", Money.of("100.00"))
                        .containsEntry("requestedAmount", Money.of("100.01")));
        assertThat(account.getBalance()).isEqualTo(Money.of("100.00"));
        assertThat(account.getUpdatedAt()).isEqualTo(OPENED_AT);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.00", "-5.00", "1.005"})
    void rejectsZeroNegativeAndOverPreciseAmounts(String amount) {
        Account account = accountWithBalance("100.00");

        assertThatIllegalArgumentException().isThrownBy(() -> account.credit(new BigDecimal(amount), LATER));
        assertThatIllegalArgumentException().isThrownBy(() -> account.debit(new BigDecimal(amount), LATER));
        assertThat(account.getBalance()).isEqualTo(Money.of("100.00"));
    }

    private static Account accountWithBalance(String balance) {
        return AccountFixtures.account(UUID.randomUUID(), "Ada Lovelace", balance, OPENED_AT);
    }
}
