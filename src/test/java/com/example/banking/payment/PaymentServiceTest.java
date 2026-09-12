package com.example.banking.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.banking.account.Account;
import com.example.banking.account.AccountFixtures;
import com.example.banking.account.AccountNotFoundException;
import com.example.banking.account.AccountRepository;
import com.example.banking.account.DailyWithdrawalLimit;
import com.example.banking.account.DailyWithdrawalLimitExceededException;
import com.example.banking.account.Ledger;
import com.example.banking.account.Posting;
import com.example.banking.common.IdempotencyKeyReusedException;
import com.example.banking.common.Money;
import com.example.banking.support.TestProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    private static final Instant NOW = Instant.parse("2026-03-10T14:00:00Z");
    private static final Duration WINDOW = Duration.ofDays(3);

    @Mock
    private ScheduledPaymentRepository payments;

    @Mock
    private AccountRepository accounts;

    @Mock
    private Ledger ledger;

    @Mock
    private DailyWithdrawalLimit dailyWithdrawalLimit;

    private final UUID targetId = UUID.randomUUID();
    private Account source;
    private PaymentService service;

    @BeforeEach
    void setUp() {
        source = AccountFixtures.account(UUID.randomUUID(), "Alice", "1000.00", NOW.minus(Duration.ofDays(30)));
        service = new PaymentService(payments, accounts, ledger, dailyWithdrawalLimit, Clock.fixed(NOW, ZoneOffset.UTC),
                TestProperties.banking(ZoneOffset.UTC, "1000.00", WINDOW));
    }

    @Test
    void immediatePaymentHoldsTheFundsUntilAccepted() {
        givenBothAccountsExist();
        givenSavedPaymentsGetIds();

        ScheduledPayment payment = service.schedulePayment(command("400.00", null, null));

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING_ACCEPTANCE);
        assertThat(payment.getExecuteAt()).isEqualTo(NOW);
        assertThat(payment.getExpiresAt()).isEqualTo(NOW.plus(WINDOW));
        verify(ledger).post(source, Posting.paymentHold(Money.of("400.00"), payment.getId()));
    }

    @Test
    void futurePaymentIsScheduledWithoutMovingMoney() {
        givenBothAccountsExist();
        givenSavedPaymentsGetIds();
        Instant tomorrow = NOW.plus(Duration.ofDays(1));

        ScheduledPayment payment = service.schedulePayment(command("400.00", tomorrow, null));

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SCHEDULED);
        assertThat(payment.getExecuteAt()).isEqualTo(tomorrow);
        verify(dailyWithdrawalLimit).verifyWithinLimit(Money.of("400.00"));
        verify(ledger, never()).post(any(), any());
    }

    @Test
    void futurePaymentAboveTheDailyLimitIsRejectedUpFront() {
        givenBothAccountsExist();
        doThrow(new DailyWithdrawalLimitExceededException(Money.of("1000.00"), Money.of("1000.00"), Money.of("1500.00")))
                .when(dailyWithdrawalLimit).verifyWithinLimit(Money.of("1500.00"));

        assertThatThrownBy(() -> service.schedulePayment(command("1500.00", NOW.plus(Duration.ofDays(1)), null)))
                .isInstanceOf(DailyWithdrawalLimitExceededException.class);

        verify(payments, never()).save(any());
    }

    @Test
    void cannotPayTheSameAccount() {
        SchedulePaymentCommand toSelf =
                new SchedulePaymentCommand(source.getId(), source.getId(), Money.of("10.00"), null, null, null);

        assertThatThrownBy(() -> service.schedulePayment(toSelf)).isInstanceOf(InvalidPaymentRequestException.class);

        verifyNoInteractions(ledger, payments);
    }

    @Test
    void executionTimeMustBeInTheFuture() {
        assertThatThrownBy(() -> service.schedulePayment(command("10.00", NOW, null)))
                .isInstanceOf(InvalidPaymentRequestException.class)
                .hasMessageContaining("executeAt must be in the future");

        verifyNoInteractions(ledger, payments);
    }

    @Test
    void targetAccountMustExist() {
        when(ledger.lockAccount(source.getId())).thenReturn(source);
        when(accounts.existsById(targetId)).thenReturn(false);

        assertThatThrownBy(() -> service.schedulePayment(command("10.00", null, null)))
                .isInstanceOf(AccountNotFoundException.class);

        verify(payments, never()).save(any());
    }

    @Test
    void retryWithTheSameIdempotencyKeyReturnsTheOriginalPayment() {
        ScheduledPayment original = PaymentFixtures.pendingAcceptance(source.getId(), targetId, "400.00", NOW, WINDOW);
        when(ledger.lockAccount(source.getId())).thenReturn(source);
        when(payments.findBySourceAccountIdAndIdempotencyKey(source.getId(), "invoice-1017"))
                .thenReturn(Optional.of(original));

        assertThat(service.schedulePayment(command("400.00", null, "invoice-1017"))).isSameAs(original);

        verify(payments, never()).save(any());
        verify(ledger, never()).post(any(), any());
    }

    @Test
    void idempotencyKeyCannotBeReusedForADifferentPayment() {
        ScheduledPayment original = PaymentFixtures.pendingAcceptance(source.getId(), targetId, "400.00", NOW, WINDOW);
        when(ledger.lockAccount(source.getId())).thenReturn(source);
        when(payments.findBySourceAccountIdAndIdempotencyKey(source.getId(), "invoice-1017"))
                .thenReturn(Optional.of(original));

        assertThatThrownBy(() -> service.schedulePayment(command("500.00", null, "invoice-1017")))
                .isInstanceOf(IdempotencyKeyReusedException.class);
    }

    @Test
    void acceptingCreditsTheTargetAccount() {
        ScheduledPayment payment =
                PaymentFixtures.pendingAcceptance(source.getId(), targetId, "400.00", NOW.minus(Duration.ofHours(1)), WINDOW);
        Account target = AccountFixtures.account(targetId, "Bob", "0.00", NOW.minus(Duration.ofDays(30)));
        when(payments.findByIdForUpdate(payment.getId())).thenReturn(Optional.of(payment));
        when(ledger.lockAccount(targetId)).thenReturn(target);

        assertThat(service.acceptPayment(payment.getId()).getStatus()).isEqualTo(PaymentStatus.COMPLETED);

        verify(ledger).post(target, Posting.paymentReceived(Money.of("400.00"), payment.getId()));
    }

    @Test
    void acceptingACompletedPaymentAgainChangesNothing() {
        ScheduledPayment payment = PaymentFixtures.completed(source.getId(), targetId, "400.00",
                NOW.minus(Duration.ofHours(2)), NOW.minus(Duration.ofHours(1)));
        when(payments.findByIdForUpdate(payment.getId())).thenReturn(Optional.of(payment));

        assertThat(service.acceptPayment(payment.getId())).isSameAs(payment);

        verifyNoInteractions(ledger);
    }

    @Test
    void acceptingAfterTheHoldExpiredReturnsTheFundsToTheSource() {
        ScheduledPayment payment = PaymentFixtures.pendingAcceptance(source.getId(), targetId, "400.00",
                NOW.minus(WINDOW).minusSeconds(1), WINDOW);
        when(payments.findByIdForUpdate(payment.getId())).thenReturn(Optional.of(payment));
        when(ledger.lockAccount(source.getId())).thenReturn(source);

        assertThatThrownBy(() -> service.acceptPayment(payment.getId())).isInstanceOf(PaymentExpiredException.class);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.EXPIRED);
        verify(ledger).post(source, Posting.paymentRefund(Money.of("400.00"), payment.getId()));
    }

    @Test
    void executionThatWouldBreachTheDailyLimitFailsThePayment() {
        ScheduledPayment payment =
                PaymentFixtures.scheduled(source.getId(), targetId, "400.00", NOW, NOW.minus(Duration.ofDays(1)));
        when(payments.claimNextDuePayment(NOW)).thenReturn(Optional.of(payment));
        when(ledger.lockAccount(source.getId())).thenReturn(source);
        when(ledger.post(eq(source), any())).thenThrow(
                new DailyWithdrawalLimitExceededException(Money.of("1000.00"), Money.of("100.00"), Money.of("400.00")));

        assertThat(service.executeNextDuePayment()).isTrue();

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        // The target account can read the payment, so the reason must not reveal the source account's figures.
        assertThat(payment.getFailureReason()).isEqualTo("Daily withdrawal limit exceeded");
    }

    @Test
    void thereIsNothingToExecuteWhenNoPaymentIsDue() {
        when(payments.claimNextDuePayment(NOW)).thenReturn(Optional.empty());

        assertThat(service.executeNextDuePayment()).isFalse();

        verifyNoInteractions(ledger);
    }

    private SchedulePaymentCommand command(String amount, @Nullable Instant executeAt, @Nullable String idempotencyKey) {
        return new SchedulePaymentCommand(source.getId(), targetId, Money.of(amount), executeAt, null, idempotencyKey);
    }

    private void givenBothAccountsExist() {
        when(ledger.lockAccount(source.getId())).thenReturn(source);
        when(accounts.existsById(targetId)).thenReturn(true);
    }

    private void givenSavedPaymentsGetIds() {
        when(payments.save(any(ScheduledPayment.class)))
                .thenAnswer(invocation -> PaymentFixtures.withId(invocation.getArgument(0)));
    }
}
