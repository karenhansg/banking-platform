package com.example.banking.payment;

import com.example.banking.account.Account;
import com.example.banking.account.AccountNotFoundException;
import com.example.banking.account.AccountRepository;
import com.example.banking.account.DailyWithdrawalLimit;
import com.example.banking.account.DailyWithdrawalLimitExceededException;
import com.example.banking.account.InsufficientFundsException;
import com.example.banking.account.Ledger;
import com.example.banking.account.Posting;
import com.example.banking.common.IdempotencyKeyReusedException;
import com.example.banking.config.BankingProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Scheduled payments: funds leave the source account when the payment executes (counting towards the daily withdrawal
 * limit), are held until the target account accepts, and go back to the source account if the hold expires.
 *
 * <p>Locks are always taken payment first, then account, which rules out deadlocks between these operations.
 */
@Service
@Transactional(readOnly = true)
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final ScheduledPaymentRepository payments;
    private final AccountRepository accounts;
    private final Ledger ledger;
    private final DailyWithdrawalLimit dailyWithdrawalLimit;
    private final Clock clock;
    private final Duration acceptanceWindow;

    public PaymentService(ScheduledPaymentRepository payments, AccountRepository accounts, Ledger ledger,
            DailyWithdrawalLimit dailyWithdrawalLimit, Clock clock, BankingProperties properties) {
        this.payments = payments;
        this.accounts = accounts;
        this.ledger = ledger;
        this.dailyWithdrawalLimit = dailyWithdrawalLimit;
        this.clock = clock;
        this.acceptanceWindow = properties.payments().acceptanceWindow();
    }

    /**
     * Creates a payment. Without an execution time the funds are held straight away, so a breach of the daily limit or
     * insufficient funds is reported to the caller and nothing is created. A future payment is checked again in full
     * when it executes.
     */
    @Transactional
    public ScheduledPayment schedulePayment(SchedulePaymentCommand command) {
        Instant now = clock.instant();
        validate(command, now);

        // Locking the source account serialises its requests, so retries cannot both pass the idempotency check.
        Account source = ledger.lockAccount(command.sourceAccountId());
        String idempotencyKey = command.idempotencyKey();
        if (idempotencyKey != null) {
            Optional<ScheduledPayment> previous =
                    payments.findBySourceAccountIdAndIdempotencyKey(source.getId(), idempotencyKey);
            if (previous.isPresent()) {
                return replay(previous.get(), command, idempotencyKey);
            }
        }
        if (!accounts.existsById(command.targetAccountId())) {
            throw new AccountNotFoundException(command.targetAccountId());
        }

        Instant executeAt = command.executeAt();
        if (executeAt == null) {
            ScheduledPayment payment = payments.save(newPayment(command, now, now));
            holdFunds(payment, source, now);
            logEvent("payment.executed", payment);
            return payment;
        }
        // The full limit check happens on the execution day, but an amount above the limit could never succeed.
        dailyWithdrawalLimit.verifyWithinLimit(command.amount());
        ScheduledPayment payment = payments.save(newPayment(command, executeAt, now));
        logEvent("payment.scheduled", payment);
        return payment;
    }

    public ScheduledPayment getPayment(UUID paymentId) {
        return payments.findById(paymentId).orElseThrow(() -> new PaymentNotFoundException(paymentId));
    }

    public Page<ScheduledPayment> findPayments(UUID accountId, @Nullable PaymentStatus status, Pageable pageable) {
        if (!accounts.existsById(accountId)) {
            throw new AccountNotFoundException(accountId);
        }
        return payments.findByAccount(accountId, status, pageable);
    }

    /**
     * Accepts a payment on behalf of the target account, which is credited with the held funds. Accepting an already
     * completed payment returns it unchanged, so clients can retry safely.
     *
     * @throws PaymentExpiredException if the hold has expired; the funds are returned to the source account
     */
    @Transactional(noRollbackFor = PaymentExpiredException.class)
    public ScheduledPayment acceptPayment(UUID paymentId) {
        Instant now = clock.instant();
        ScheduledPayment payment = lockPayment(paymentId);
        if (payment.getStatus() == PaymentStatus.COMPLETED) {
            return payment;
        }
        if (payment.getStatus() == PaymentStatus.EXPIRED) {
            throw new PaymentExpiredException(payment.getId(), payment.getExpiresAt());
        }
        if (payment.isOverdue(now)) {
            // Expired but not yet released by the scheduler: release it now so the caller gets a definitive answer.
            releaseHold(payment, now);
            throw new PaymentExpiredException(payment.getId(), payment.getExpiresAt());
        }
        payment.complete(now);
        Account target = ledger.lockAccount(payment.getTargetAccountId());
        ledger.post(target, Posting.paymentReceived(payment.getAmount(), payment.getId()));
        logEvent("payment.completed", payment);
        return payment;
    }

    /** Cancels a payment that has not executed yet. Cancelling an already cancelled payment returns it unchanged. */
    @Transactional
    public ScheduledPayment cancelPayment(UUID paymentId) {
        ScheduledPayment payment = lockPayment(paymentId);
        if (payment.getStatus() != PaymentStatus.CANCELLED) {
            payment.cancel(clock.instant());
            logEvent("payment.cancelled", payment);
        }
        return payment;
    }

    /**
     * Executes the next due payment, if any, in its own transaction.
     *
     * @return whether a payment was processed
     */
    @Transactional
    public boolean executeNextDuePayment() {
        Instant now = clock.instant();
        Optional<ScheduledPayment> next = payments.claimNextDuePayment(now);
        next.ifPresent(payment -> execute(payment, now));
        return next.isPresent();
    }

    /**
     * Returns the held funds of the next overdue payment, if any, in its own transaction.
     *
     * @return whether a payment was processed
     */
    @Transactional
    public boolean expireNextOverduePayment() {
        Instant now = clock.instant();
        Optional<ScheduledPayment> next = payments.claimNextOverduePayment(now);
        next.ifPresent(payment -> releaseHold(payment, now));
        return next.isPresent();
    }

    private void execute(ScheduledPayment payment, Instant now) {
        Account source = ledger.lockAccount(payment.getSourceAccountId());
        try {
            holdFunds(payment, source, now);
            logEvent("payment.executed", payment);
        } catch (InsufficientFundsException | DailyWithdrawalLimitExceededException e) {
            // The target account can read this payment, so record the reason without the source's balance details.
            payment.fail(e.errorCode().title(), now);
            logEvent("payment.failed", payment);
        }
    }

    private void holdFunds(ScheduledPayment payment, Account source, Instant now) {
        ledger.post(source, Posting.paymentHold(payment.getAmount(), payment.getId()));
        payment.hold(now, acceptanceWindow);
    }

    private void releaseHold(ScheduledPayment payment, Instant now) {
        payment.expire(now);
        Account source = ledger.lockAccount(payment.getSourceAccountId());
        ledger.post(source, Posting.paymentRefund(payment.getAmount(), payment.getId()));
        logEvent("payment.expired", payment);
    }

    private ScheduledPayment lockPayment(UUID paymentId) {
        return payments.findByIdForUpdate(paymentId).orElseThrow(() -> new PaymentNotFoundException(paymentId));
    }

    private static void validate(SchedulePaymentCommand command, Instant now) {
        if (command.sourceAccountId().equals(command.targetAccountId())) {
            throw new InvalidPaymentRequestException("Source and target accounts must be different");
        }
        Instant executeAt = command.executeAt();
        if (executeAt != null && !executeAt.isAfter(now)) {
            throw new InvalidPaymentRequestException("executeAt must be in the future; omit it to pay immediately");
        }
    }

    private static ScheduledPayment replay(ScheduledPayment previous, SchedulePaymentCommand command,
            String idempotencyKey) {
        boolean samePayment = previous.getTargetAccountId().equals(command.targetAccountId())
                && previous.getAmount().compareTo(command.amount()) == 0;
        if (!samePayment) {
            throw new IdempotencyKeyReusedException(idempotencyKey);
        }
        return previous;
    }

    private static ScheduledPayment newPayment(SchedulePaymentCommand command, Instant executeAt, Instant now) {
        return new ScheduledPayment(command.sourceAccountId(), command.targetAccountId(), command.amount(), executeAt,
                command.description(), command.idempotencyKey(), now);
    }

    private static void logEvent(String event, ScheduledPayment payment) {
        log.atInfo()
                .addKeyValue("event", event)
                .addKeyValue("paymentId", payment.getId())
                .addKeyValue("status", payment.getStatus())
                .log("Payment {} is {}", payment.getId(), payment.getStatus());
    }
}
