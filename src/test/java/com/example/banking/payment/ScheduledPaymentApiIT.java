package com.example.banking.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.banking.common.Money;
import com.example.banking.config.BankingProperties;
import com.example.banking.support.BankingApi;
import com.example.banking.support.IntegrationTest;
import com.example.banking.support.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
class ScheduledPaymentApiIT {

    private static final int UNPROCESSABLE = HttpStatus.UNPROCESSABLE_CONTENT.value();

    @Autowired
    private MockMvc mvc;

    @Autowired
    private MutableClock clock;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private BankingProperties properties;

    private BankingApi api;

    @BeforeEach
    void setUp() {
        api = new BankingApi(mvc);
        clock.setInstant(localTime("2026-03-10T10:00"));
    }

    @Test
    void immediatePaymentHoldsTheFundsUntilTheTargetAccepts() throws Exception {
        String alice = api.openAccountWithBalance("Alice", "1000.00");
        String bob = api.openAccount("Bob");
        Instant expectedExpiry = clock.instant().plus(properties.payments().acceptanceWindow());

        String paymentId = api.createdId(api.schedulePayment(alice, bob, "400.00", null, null)
                .andExpect(header().string("Location", startsWith("/api/v1/payments/")))
                .andExpect(jsonPath("$.status").value("PENDING_ACCEPTANCE"))
                .andExpect(jsonPath("$.expiresAt").value(expectedExpiry.toString())));

        assertThat(api.balanceOf(alice)).isEqualTo(Money.of("600.00"));
        assertThat(api.balanceOf(bob)).isEqualTo(Money.of("0.00"));

        clock.advance(Duration.ofHours(1));
        api.acceptPayment(paymentId).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETED"));
        assertThat(api.balanceOf(bob)).isEqualTo(Money.of("400.00"));

        // Accepting again, e.g. a client retry, changes nothing.
        api.acceptPayment(paymentId).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETED"));
        assertThat(api.balanceOf(bob)).isEqualTo(Money.of("400.00"));
        assertThat(api.balanceOf(alice)).isEqualTo(Money.of("600.00"));
    }

    @Test
    void heldFundsReturnToTheSourceWhenThePaymentExpires() throws Exception {
        String alice = api.openAccountWithBalance("Alice", "1000.00");
        String bob = api.openAccount("Bob");
        String paymentId = api.createdId(api.schedulePayment(alice, bob, "400.00", null, null));

        clock.advance(properties.payments().acceptanceWindow());
        processScheduledPayments();

        api.payment(paymentId).andExpect(jsonPath("$.status").value("EXPIRED"));
        assertThat(api.balanceOf(alice)).isEqualTo(Money.of("1000.00"));
        api.acceptPayment(paymentId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYMENT_EXPIRED"));
        assertThat(api.balanceOf(bob)).isEqualTo(Money.of("0.00"));
    }

    @Test
    void acceptingAfterExpiryReturnsTheFundsEvenBeforeTheSchedulerRuns() throws Exception {
        String alice = api.openAccountWithBalance("Alice", "1000.00");
        String bob = api.openAccount("Bob");
        String paymentId = api.createdId(api.schedulePayment(alice, bob, "400.00", null, null));

        clock.advance(properties.payments().acceptanceWindow().plusMinutes(1));

        api.acceptPayment(paymentId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYMENT_EXPIRED"));
        api.payment(paymentId).andExpect(jsonPath("$.status").value("EXPIRED"));
        assertThat(api.balanceOf(alice)).isEqualTo(Money.of("1000.00"));
        assertThat(api.balanceOf(bob)).isEqualTo(Money.of("0.00"));
    }

    @Test
    void paymentsCountTowardsTheDailyWithdrawalLimit() throws Exception {
        String alice = api.openAccountWithBalance("Alice", "3000.00");
        String bob = api.openAccount("Bob");

        api.withdraw(alice, "800.00").andExpect(status().isCreated());
        api.schedulePayment(alice, bob, "300.00", null, null)
                .andExpect(status().is(UNPROCESSABLE))
                .andExpect(jsonPath("$.code").value("DAILY_WITHDRAWAL_LIMIT_EXCEEDED"))
                .andExpect(jsonPath("$.remainingToday").value(200.00));
        api.schedulePayment(alice, bob, "200.00", null, null).andExpect(status().isCreated());
        api.withdraw(alice, "0.01").andExpect(status().is(UNPROCESSABLE));

        // The rejected payment was never created.
        api.paymentsOf(alice, null).andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void futurePaymentExecutesWhenDueAgainstThatDaysLimit() throws Exception {
        String alice = api.openAccountWithBalance("Alice", "3000.00");
        String bob = api.openAccount("Bob");
        // Today's limit is used up, which does not matter for a payment that executes tomorrow.
        api.withdraw(alice, "1000.00").andExpect(status().isCreated());
        Instant tomorrowMorning = localTime("2026-03-11T09:00");

        String paymentId = api.createdId(api.schedulePayment(alice, bob, "250.00", tomorrowMorning, null)
                .andExpect(jsonPath("$.status").value("SCHEDULED")));

        clock.setInstant(tomorrowMorning.minusSeconds(60));
        processScheduledPayments();
        api.payment(paymentId).andExpect(jsonPath("$.status").value("SCHEDULED"));
        assertThat(api.balanceOf(alice)).isEqualTo(Money.of("2000.00"));

        clock.setInstant(tomorrowMorning);
        processScheduledPayments();
        api.payment(paymentId).andExpect(jsonPath("$.status").value("PENDING_ACCEPTANCE"));
        assertThat(api.balanceOf(alice)).isEqualTo(Money.of("1750.00"));
    }

    @Test
    void futurePaymentFailsIfTheDailyLimitIsUsedUpOnItsExecutionDay() throws Exception {
        String alice = api.openAccountWithBalance("Alice", "3000.00");
        String bob = api.openAccount("Bob");
        Instant tomorrowMorning = localTime("2026-03-11T09:00");
        String paymentId = api.createdId(api.schedulePayment(alice, bob, "500.00", tomorrowMorning, null));

        clock.setInstant(localTime("2026-03-11T08:00"));
        api.withdraw(alice, "600.00").andExpect(status().isCreated());
        clock.setInstant(tomorrowMorning);
        processScheduledPayments();

        api.payment(paymentId)
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failureReason").value("Daily withdrawal limit exceeded"));
        assertThat(api.balanceOf(alice)).isEqualTo(Money.of("2400.00"));
    }

    @Test
    void futurePaymentFailsIfFundsAreInsufficientWhenItExecutes() throws Exception {
        String alice = api.openAccountWithBalance("Alice", "300.00");
        String bob = api.openAccount("Bob");
        Instant tomorrowMorning = localTime("2026-03-11T09:00");
        String paymentId = api.createdId(api.schedulePayment(alice, bob, "250.00", tomorrowMorning, null));
        api.withdraw(alice, "100.00").andExpect(status().isCreated());

        clock.setInstant(tomorrowMorning);
        processScheduledPayments();

        api.payment(paymentId)
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failureReason").value("Insufficient funds"));
        assertThat(api.balanceOf(alice)).isEqualTo(Money.of("200.00"));
    }

    @Test
    void paymentCanBeCancelledUntilItExecutes() throws Exception {
        String alice = api.openAccountWithBalance("Alice", "1000.00");
        String bob = api.openAccount("Bob");
        Instant tomorrowMorning = localTime("2026-03-11T09:00");
        String paymentId = api.createdId(api.schedulePayment(alice, bob, "250.00", tomorrowMorning, null));

        api.cancelPayment(paymentId).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        api.cancelPayment(paymentId).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));

        clock.setInstant(tomorrowMorning);
        processScheduledPayments();
        api.payment(paymentId).andExpect(jsonPath("$.status").value("CANCELLED"));
        assertThat(api.balanceOf(alice)).isEqualTo(Money.of("1000.00"));
    }

    @Test
    void paymentCannotBeCancelledOnceItHoldsFunds() throws Exception {
        String alice = api.openAccountWithBalance("Alice", "1000.00");
        String bob = api.openAccount("Bob");
        String paymentId = api.createdId(api.schedulePayment(alice, bob, "250.00", null, null));

        api.cancelPayment(paymentId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_PAYMENT_STATE"))
                .andExpect(jsonPath("$.paymentStatus").value("PENDING_ACCEPTANCE"));
    }

    @Test
    void rejectsInvalidPaymentRequestsWithoutCreatingAnything() throws Exception {
        String alice = api.openAccountWithBalance("Alice", "1000.00");
        String bob = api.openAccount("Bob");

        api.schedulePayment(alice, alice, "10.00", null, null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PAYMENT_REQUEST"));
        api.schedulePayment(alice, bob, "10.00", clock.instant().minusSeconds(1), null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PAYMENT_REQUEST"));
        api.schedulePayment(alice, bob, "1000.01", localTime("2026-03-11T09:00"), null)
                .andExpect(status().is(UNPROCESSABLE))
                .andExpect(jsonPath("$.code").value("DAILY_WITHDRAWAL_LIMIT_EXCEEDED"));
        api.schedulePayment(alice, UUID.randomUUID().toString(), "10.00", null, null)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));
        api.schedulePayment(bob, alice, "10.00", null, null)
                .andExpect(status().is(UNPROCESSABLE))
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"));

        api.paymentsOf(alice, null).andExpect(jsonPath("$.totalElements").value(0));
        assertThat(api.balanceOf(alice)).isEqualTo(Money.of("1000.00"));
    }

    @Test
    void retriedPaymentWithTheSameIdempotencyKeyIsCreatedOnce() throws Exception {
        String alice = api.openAccountWithBalance("Alice", "1000.00");
        String bob = api.openAccount("Bob");

        String firstId = api.createdId(api.schedulePayment(alice, bob, "400.00", null, "invoice-1017"));
        String retryId = api.createdId(api.schedulePayment(alice, bob, "400.00", null, "invoice-1017"));

        assertThat(retryId).isEqualTo(firstId);
        assertThat(api.balanceOf(alice)).isEqualTo(Money.of("600.00"));
        api.schedulePayment(alice, bob, "500.00", null, "invoice-1017")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void targetAccountCanFindItsIncomingPaymentsByStatus() throws Exception {
        String alice = api.openAccountWithBalance("Alice", "1000.00");
        String carol = api.openAccountWithBalance("Carol", "1000.00");
        String bob = api.openAccount("Bob");
        api.schedulePayment(alice, bob, "100.00", null, null).andExpect(status().isCreated());
        api.schedulePayment(carol, bob, "50.00", localTime("2026-03-11T09:00"), null).andExpect(status().isCreated());

        api.paymentsOf(bob, "PENDING_ACCEPTANCE")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].sourceAccountId").value(alice));
        api.paymentsOf(bob, null).andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    void concurrentSchedulerInstancesProcessEachDuePaymentOnce() throws Exception {
        String alice = api.openAccountWithBalance("Alice", "1000.00");
        String bob = api.openAccount("Bob");
        Instant executeAt = clock.instant().plus(Duration.ofHours(1));
        for (int i = 0; i < 5; i++) {
            api.schedulePayment(alice, bob, "100.00", executeAt, null).andExpect(status().isCreated());
        }
        clock.setInstant(executeAt);

        ExecutorService instances = Executors.newFixedThreadPool(4);
        try {
            List<Future<?>> runs = IntStream.range(0, 4)
                    .<Future<?>>mapToObj(i -> instances.submit(this::processScheduledPayments))
                    .toList();
            for (Future<?> run : runs) {
                run.get(30, TimeUnit.SECONDS);
            }
        } finally {
            instances.shutdownNow();
        }

        assertThat(api.balanceOf(alice)).isEqualTo(Money.of("500.00"));
        api.paymentsOf(alice, "PENDING_ACCEPTANCE").andExpect(jsonPath("$.totalElements").value(5));
        api.transactions(alice).andExpect(jsonPath("$.totalElements").value(6));
    }

    /** Does what the background scheduler does on each run, until nothing is left to process. */
    private void processScheduledPayments() {
        drain(paymentService::expireNextOverduePayment);
        drain(paymentService::executeNextDuePayment);
    }

    private static void drain(BooleanSupplier processNext) {
        boolean processed;
        do {
            processed = processNext.getAsBoolean();
        } while (processed);
    }

    private Instant localTime(String localDateTime) {
        return LocalDateTime.parse(localDateTime).atZone(properties.businessTimeZone()).toInstant();
    }
}
