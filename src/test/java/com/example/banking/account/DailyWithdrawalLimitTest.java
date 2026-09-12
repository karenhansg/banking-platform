package com.example.banking.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.banking.common.Money;
import com.example.banking.support.TestProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DailyWithdrawalLimitTest {

    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    private static final UUID ACCOUNT_ID = UUID.randomUUID();
    /** 10:00 on 10 March 2026 in New York (EDT, UTC-4). */
    private static final Instant NOW = Instant.parse("2026-03-10T14:00:00Z");

    @Mock
    private AccountTransactionRepository transactions;

    @Captor
    private ArgumentCaptor<Collection<TransactionType>> countedTypes;

    @Captor
    private ArgumentCaptor<Instant> from;

    @Captor
    private ArgumentCaptor<Instant> to;

    @Test
    void firstWithdrawalMayUseTheWholeLimit() {
        givenWithdrawnToday(null);

        assertThatNoException().isThrownBy(() -> limitAt(NOW).verify(ACCOUNT_ID, Money.of("1000.00")));
    }

    @Test
    void firstWithdrawalAboveTheLimitIsRejectedWithAdviceToContactTheBank() {
        givenWithdrawnToday(null);

        assertThatThrownBy(() -> limitAt(NOW).verify(ACCOUNT_ID, Money.of("1000.01")))
                .isInstanceOfSatisfying(DailyWithdrawalLimitExceededException.class, e -> {
                    assertThat(e).hasMessageContaining("Please contact the bank for assistance");
                    assertThat(e.details())
                            .containsEntry("dailyLimit", Money.of("1000.00"))
                            .containsEntry("remainingToday", Money.of("1000.00"))
                            .containsEntry("requestedAmount", Money.of("1000.01"));
                });
    }

    @Test
    void laterWithdrawalsMayOnlyUseWhatIsLeftOfTheLimit() {
        // 500 and then 200 already withdrawn today, so 300 remains.
        givenWithdrawnToday("700.00");
        DailyWithdrawalLimit limit = limitAt(NOW);

        assertThatNoException().isThrownBy(() -> limit.verify(ACCOUNT_ID, Money.of("300.00")));
        assertThatThrownBy(() -> limit.verify(ACCOUNT_ID, Money.of("300.01")))
                .isInstanceOfSatisfying(DailyWithdrawalLimitExceededException.class,
                        e -> assertThat(e.details()).containsEntry("remainingToday", Money.of("300.00")));
    }

    @Test
    void nothingRemainsOnceTheLimitIsReached() {
        givenWithdrawnToday("1000.00");
        DailyWithdrawalLimit limit = limitAt(NOW);

        assertThat(limit.remainingToday(ACCOUNT_ID)).isEqualTo(Money.of("0.00"));
        assertThatThrownBy(() -> limit.verify(ACCOUNT_ID, Money.of("0.01")))
                .isInstanceOf(DailyWithdrawalLimitExceededException.class);
    }

    @Test
    void remainingAllowanceIsNeverNegative() {
        // Possible if the bank lowers the limit during the day.
        givenWithdrawnToday("1200.00");

        assertThat(limitAt(NOW).remainingToday(ACCOUNT_ID)).isEqualTo(Money.of("0.00"));
    }

    @Test
    void countsWithdrawalsAndScheduledPaymentsButNotDepositsOrRefunds() {
        givenWithdrawnToday(null);

        limitAt(NOW).remainingToday(ACCOUNT_ID);

        verify(transactions).sumAmounts(eq(ACCOUNT_ID), countedTypes.capture(), any(), any());
        assertThat(countedTypes.getValue())
                .containsExactlyInAnyOrder(TransactionType.WITHDRAWAL, TransactionType.PAYMENT_HOLD);
    }

    @Test
    void bankingDayRunsFromMidnightToMidnightInTheBankTimeZone() {
        assertBankingDay(NOW, "2026-03-10T04:00:00Z", "2026-03-11T04:00:00Z");
    }

    @Test
    void oneSecondBeforeMidnightIsStillTheSameBankingDay() {
        assertBankingDay(Instant.parse("2026-03-11T03:59:59Z"), "2026-03-10T04:00:00Z", "2026-03-11T04:00:00Z");
    }

    @Test
    void bankingDayFollowsTheLocalCalendarWhenTheClocksChange() {
        // Daylight saving time starts on 8 March 2026 in New York, so that banking day is 23 hours long.
        assertBankingDay(Instant.parse("2026-03-08T12:00:00Z"), "2026-03-08T05:00:00Z", "2026-03-09T04:00:00Z");
    }

    @Test
    void anAmountAboveTheLimitCanNeverBeWithdrawnInOneDay() {
        DailyWithdrawalLimit limit = limitAt(NOW);

        assertThatNoException().isThrownBy(() -> limit.verifyWithinLimit(Money.of("1000.00")));
        assertThatThrownBy(() -> limit.verifyWithinLimit(Money.of("1000.01")))
                .isInstanceOf(DailyWithdrawalLimitExceededException.class);
        verifyNoInteractions(transactions);
    }

    private void assertBankingDay(Instant now, String expectedStart, String expectedEnd) {
        givenWithdrawnToday(null);

        limitAt(now).remainingToday(ACCOUNT_ID);

        verify(transactions).sumAmounts(eq(ACCOUNT_ID), anyCollection(), from.capture(), to.capture());
        assertThat(from.getValue()).isEqualTo(Instant.parse(expectedStart));
        assertThat(to.getValue()).isEqualTo(Instant.parse(expectedEnd));
    }

    private DailyWithdrawalLimit limitAt(Instant now) {
        return new DailyWithdrawalLimit(transactions, Clock.fixed(now, ZoneOffset.UTC),
                TestProperties.banking(NEW_YORK, "1000.00", Duration.ofDays(3)));
    }

    private void givenWithdrawnToday(@Nullable String amount) {
        when(transactions.sumAmounts(eq(ACCOUNT_ID), anyCollection(), any(), any()))
                .thenReturn(Optional.ofNullable(amount).map(Money::of));
    }
}
