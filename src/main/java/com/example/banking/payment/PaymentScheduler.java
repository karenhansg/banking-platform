package com.example.banking.payment;

import com.example.banking.config.BankingProperties;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.stereotype.Component;

/**
 * Background job that expires overdue holds and executes due payments.
 *
 * <p>Every application instance runs it. Each payment is claimed with {@code FOR UPDATE SKIP LOCKED} and processed in
 * its own transaction, so instances never process the same payment twice and a failure affects only one payment.
 */
@Component
@ConditionalOnBooleanProperty(name = "banking.payments.scheduler-enabled", matchIfMissing = true)
class PaymentScheduler implements SchedulingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(PaymentScheduler.class);

    private final PaymentService paymentService;
    private final BankingProperties.Payments settings;

    PaymentScheduler(PaymentService paymentService, BankingProperties properties) {
        this.paymentService = paymentService;
        this.settings = properties.payments();
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.addFixedDelayTask(this::processPayments, settings.schedulerInterval());
    }

    /** Expires overdue holds first, because the returned funds may be needed by payments that are now due. */
    void processPayments() {
        try {
            int expired = processUpTo(settings.schedulerBatchSize(), paymentService::expireNextOverduePayment);
            int executed = processUpTo(settings.schedulerBatchSize(), paymentService::executeNextDuePayment);
            if (expired > 0 || executed > 0) {
                log.info("Payment scheduler expired {} and executed {} payments", expired, executed);
            }
        } catch (RuntimeException e) {
            // Nothing is lost: unprocessed payments stay claimable and are retried on the next run.
            log.error("Payment scheduler run failed", e);
        }
    }

    private static int processUpTo(int limit, BooleanSupplier processNext) {
        int processed = 0;
        while (processed < limit && processNext.getAsBoolean()) {
            processed++;
        }
        return processed;
    }
}
