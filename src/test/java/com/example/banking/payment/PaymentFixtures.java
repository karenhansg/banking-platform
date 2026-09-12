package com.example.banking.payment;

import com.example.banking.common.Money;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.test.util.ReflectionTestUtils;

/** Builds payments in a given state, as if they had been loaded from the database. */
public final class PaymentFixtures {

    private PaymentFixtures() {
    }

    public static ScheduledPayment scheduled(UUID sourceAccountId, UUID targetAccountId, String amount,
            Instant executeAt, Instant createdAt) {
        return withId(new ScheduledPayment(sourceAccountId, targetAccountId, Money.of(amount), executeAt, null, null,
                createdAt));
    }

    public static ScheduledPayment pendingAcceptance(UUID sourceAccountId, UUID targetAccountId, String amount,
            Instant heldAt, Duration acceptanceWindow) {
        ScheduledPayment payment = scheduled(sourceAccountId, targetAccountId, amount, heldAt, heldAt);
        payment.hold(heldAt, acceptanceWindow);
        return payment;
    }

    public static ScheduledPayment completed(UUID sourceAccountId, UUID targetAccountId, String amount,
            Instant heldAt, Instant acceptedAt) {
        ScheduledPayment payment = pendingAcceptance(sourceAccountId, targetAccountId, amount, heldAt,
                Duration.ofDays(3));
        payment.complete(acceptedAt);
        return payment;
    }

    static ScheduledPayment withId(ScheduledPayment payment) {
        ReflectionTestUtils.setField(payment, "id", UUID.randomUUID());
        return payment;
    }
}
