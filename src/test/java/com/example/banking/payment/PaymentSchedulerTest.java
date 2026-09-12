package com.example.banking.payment;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.banking.common.Money;
import com.example.banking.config.BankingProperties;
import java.time.Duration;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PaymentSchedulerTest {

    private static final int BATCH_SIZE = 3;

    @Mock
    private PaymentService paymentService;

    private PaymentScheduler scheduler;

    @BeforeEach
    void setUp() {
        BankingProperties properties = new BankingProperties(ZoneOffset.UTC, Money.of("1000.00"),
                new BankingProperties.Payments(Duration.ofDays(3), true, Duration.ofSeconds(15), BATCH_SIZE));
        scheduler = new PaymentScheduler(paymentService, properties);
    }

    @Test
    void releasesOverdueHoldsBeforeExecutingDuePayments() {
        when(paymentService.expireNextOverduePayment()).thenReturn(true, false);
        when(paymentService.executeNextDuePayment()).thenReturn(true, false);

        scheduler.processPayments();

        InOrder inOrder = inOrder(paymentService);
        inOrder.verify(paymentService, times(2)).expireNextOverduePayment();
        inOrder.verify(paymentService, times(2)).executeNextDuePayment();
    }

    @Test
    void processesAtMostOneBatchPerRun() {
        when(paymentService.expireNextOverduePayment()).thenReturn(false);
        when(paymentService.executeNextDuePayment()).thenReturn(true);

        scheduler.processPayments();

        verify(paymentService, times(BATCH_SIZE)).executeNextDuePayment();
    }

    @Test
    void failedRunIsContainedSoTheNextRunCanRetry() {
        when(paymentService.expireNextOverduePayment()).thenThrow(new IllegalStateException("database unavailable"));

        assertThatNoException().isThrownBy(scheduler::processPayments);

        verify(paymentService, never()).executeNextDuePayment();
    }
}
