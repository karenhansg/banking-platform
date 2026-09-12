package com.example.banking.account;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.banking.common.Money;
import com.example.banking.support.IntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Many simultaneous withdrawals from one account, as a double-clicking or retrying client would send them. */
@IntegrationTest
class ConcurrentWithdrawalIT {

    private static final int SIMULTANEOUS_WITHDRAWALS = 20;

    @Autowired
    private AccountService accountService;

    @Test
    void simultaneousWithdrawalsCannotExceedTheDailyLimit() throws Exception {
        UUID accountId = openAccountWithBalance("5000.00");

        List<Throwable> failures = withdrawSimultaneously(accountId, "150.00");

        // 6 x 150 = 900 fits within the limit; a seventh withdrawal would take the day's total to 1050.
        assertThat(failures)
                .hasSize(SIMULTANEOUS_WITHDRAWALS - 6)
                .allSatisfy(failure -> assertThat(failure).isInstanceOf(DailyWithdrawalLimitExceededException.class));
        assertThat(accountService.getBalance(accountId).balance()).isEqualTo(Money.of("4100.00"));
    }

    @Test
    void simultaneousWithdrawalsCannotOverdrawTheAccount() throws Exception {
        UUID accountId = openAccountWithBalance("500.00");

        List<Throwable> failures = withdrawSimultaneously(accountId, "100.00");

        assertThat(failures)
                .hasSize(SIMULTANEOUS_WITHDRAWALS - 5)
                .allSatisfy(failure -> assertThat(failure).isInstanceOf(InsufficientFundsException.class));
        assertThat(accountService.getBalance(accountId).balance()).isEqualTo(Money.of("0.00"));
    }

    private UUID openAccountWithBalance(String balance) {
        UUID accountId = accountService.openAccount("Grace Hopper").getId();
        accountService.deposit(accountId, Money.of(balance), null, null);
        return accountId;
    }

    /** Releases all withdrawals at the same moment and returns the ones that failed. */
    private List<Throwable> withdrawSimultaneously(UUID accountId, String amount) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(SIMULTANEOUS_WITHDRAWALS);
        CountDownLatch startingGun = new CountDownLatch(1);
        try {
            List<Future<AccountTransaction>> withdrawals = IntStream.range(0, SIMULTANEOUS_WITHDRAWALS)
                    .mapToObj(i -> executor.submit(() -> {
                        startingGun.await();
                        return accountService.withdraw(accountId, Money.of(amount), null, null);
                    }))
                    .toList();
            startingGun.countDown();

            List<Throwable> failures = new ArrayList<>();
            for (Future<AccountTransaction> withdrawal : withdrawals) {
                try {
                    withdrawal.get(30, TimeUnit.SECONDS);
                } catch (ExecutionException e) {
                    failures.add(e.getCause());
                }
            }
            return failures;
        } finally {
            executor.shutdownNow();
        }
    }
}
