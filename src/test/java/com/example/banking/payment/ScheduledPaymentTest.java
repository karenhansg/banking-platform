package com.example.banking.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.banking.common.Money;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ScheduledPaymentTest {

    private static final Instant NOW = Instant.parse("2026-03-10T14:00:00Z");
    private static final Duration WINDOW = Duration.ofDays(3);
    private static final UUID SOURCE = UUID.randomUUID();
    private static final UUID TARGET = UUID.randomUUID();

    @Test
    void newPaymentIsScheduledAndHoldsNothing() {
        ScheduledPayment payment = new ScheduledPayment(SOURCE, TARGET, Money.of("250.00"), NOW.plusSeconds(3600),
                "Rent", "rent-2026-03", NOW);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SCHEDULED);
        assertThat(payment.getHeldAt()).isNull();
        assertThat(payment.getExpiresAt()).isNull();
        assertThat(payment.getDescription()).isEqualTo("Rent");
        assertThat(payment.getIdempotencyKey()).isEqualTo("rent-2026-03");
    }

    @Test
    void cannotPayTheSameAccount() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ScheduledPayment(SOURCE, SOURCE, Money.of("1.00"), NOW, null, null, NOW));
    }

    @Test
    void holdingFundsStartsTheAcceptanceWindow() {
        ScheduledPayment payment = PaymentFixtures.scheduled(SOURCE, TARGET, "250.00", NOW, NOW);

        payment.hold(NOW, WINDOW);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING_ACCEPTANCE);
        assertThat(payment.getHeldAt()).isEqualTo(NOW);
        assertThat(payment.getExpiresAt()).isEqualTo(NOW.plus(WINDOW));
    }

    @Test
    void acceptingWithinTheWindowCompletesThePayment() {
        ScheduledPayment payment = PaymentFixtures.pendingAcceptance(SOURCE, TARGET, "250.00", NOW, WINDOW);
        Instant lastMoment = NOW.plus(WINDOW).minusMillis(1);

        payment.complete(lastMoment);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(payment.getResolvedAt()).isEqualTo(lastMoment);
    }

    @Test
    void holdBecomesOverdueExactlyAtItsExpiryTime() {
        ScheduledPayment payment = PaymentFixtures.pendingAcceptance(SOURCE, TARGET, "250.00", NOW, WINDOW);

        assertThat(payment.isOverdue(NOW.plus(WINDOW).minusMillis(1))).isFalse();
        assertThat(payment.isOverdue(NOW.plus(WINDOW))).isTrue();
    }

    @Test
    void cannotBeAcceptedOnceTheHoldIsOverdue() {
        ScheduledPayment payment = PaymentFixtures.pendingAcceptance(SOURCE, TARGET, "250.00", NOW, WINDOW);

        assertThatThrownBy(() -> payment.complete(NOW.plus(WINDOW))).isInstanceOf(PaymentExpiredException.class);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING_ACCEPTANCE);
    }

    @Test
    void expiresOnlyOnceTheHoldIsOverdue() {
        ScheduledPayment payment = PaymentFixtures.pendingAcceptance(SOURCE, TARGET, "250.00", NOW, WINDOW);

        assertThatIllegalStateException().isThrownBy(() -> payment.expire(NOW.plus(WINDOW).minusSeconds(1)));

        payment.expire(NOW.plus(WINDOW));
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.EXPIRED);
        assertThat(payment.getResolvedAt()).isEqualTo(NOW.plus(WINDOW));
    }

    @Test
    void canBeCancelledOnlyBeforeItExecutes() {
        ScheduledPayment scheduled = PaymentFixtures.scheduled(SOURCE, TARGET, "250.00", NOW.plusSeconds(60), NOW);
        scheduled.cancel(NOW);
        assertThat(scheduled.getStatus()).isEqualTo(PaymentStatus.CANCELLED);

        ScheduledPayment held = PaymentFixtures.pendingAcceptance(SOURCE, TARGET, "250.00", NOW, WINDOW);
        assertThatThrownBy(() -> held.cancel(NOW))
                .isInstanceOfSatisfying(InvalidPaymentStateException.class, e -> assertThat(e.details())
                        .containsEntry("paymentStatus", PaymentStatus.PENDING_ACCEPTANCE));
    }

    @Test
    void failureRecordsTheReason() {
        ScheduledPayment payment = PaymentFixtures.scheduled(SOURCE, TARGET, "250.00", NOW, NOW);

        payment.fail("Insufficient funds", NOW);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(payment.getFailureReason()).isEqualTo("Insufficient funds");
        assertThat(payment.getResolvedAt()).isEqualTo(NOW);
    }

    @Test
    void completedPaymentCannotExecuteAgain() {
        ScheduledPayment payment = PaymentFixtures.completed(SOURCE, TARGET, "250.00", NOW, NOW.plusSeconds(60));

        assertThatThrownBy(() -> payment.hold(NOW.plusSeconds(120), WINDOW))
                .isInstanceOf(InvalidPaymentStateException.class)
                .hasMessageContaining("COMPLETED");
    }
}
