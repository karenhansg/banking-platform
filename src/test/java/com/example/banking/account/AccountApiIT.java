package com.example.banking.account;

import static com.example.banking.config.Scopes.ACCOUNTS_READ;
import static com.example.banking.support.TestJwt.withScopes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
class AccountApiIT {

    private static final int UNPROCESSABLE = HttpStatus.UNPROCESSABLE_CONTENT.value();

    @Autowired
    private MockMvc mvc;

    @Autowired
    private MutableClock clock;

    @Autowired
    private BankingProperties properties;

    private BankingApi api;

    @BeforeEach
    void setUp() {
        api = new BankingApi(mvc);
        clock.setInstant(localTime("2026-03-10T10:00"));
    }

    @Test
    void customerCanDepositWithdrawAndSeeTheirBalanceAndHistory() throws Exception {
        String accountId = api.openAccount("Ada Lovelace");

        api.deposit(accountId, "1500.00")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.balanceAfter").value(1500.00));
        clock.advance(Duration.ofMinutes(1));
        api.withdraw(accountId, "250.25")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("WITHDRAWAL"))
                .andExpect(jsonPath("$.balanceAfter").value(1249.75));

        api.balance(accountId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(1249.75))
                .andExpect(jsonPath("$.dailyWithdrawalLimit").value(1000.00))
                .andExpect(jsonPath("$.remainingDailyWithdrawalLimit").value(749.75));
        api.transactions(accountId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].type").value("WITHDRAWAL"))
                .andExpect(jsonPath("$.content[1].type").value("DEPOSIT"));
    }

    @Test
    void firstWithdrawalOfTheDayCannotExceedTheLimit() throws Exception {
        String accountId = api.openAccountWithBalance("Ada Lovelace", "5000.00");

        api.withdraw(accountId, "1000.01")
                .andExpect(status().is(UNPROCESSABLE))
                .andExpect(jsonPath("$.code").value("DAILY_WITHDRAWAL_LIMIT_EXCEEDED"))
                .andExpect(jsonPath("$.detail").value(containsString("Please contact the bank for assistance")));
        api.withdraw(accountId, "1000.00").andExpect(status().isCreated());
    }

    @Test
    void laterWithdrawalsCanOnlyUseWhatIsLeftOfTheDailyLimit() throws Exception {
        String accountId = api.openAccountWithBalance("Ada Lovelace", "5000.00");

        api.withdraw(accountId, "500.00").andExpect(status().isCreated());
        api.withdraw(accountId, "200.00").andExpect(status().isCreated());
        api.withdraw(accountId, "300.01")
                .andExpect(status().is(UNPROCESSABLE))
                .andExpect(jsonPath("$.remainingToday").value(300.00));
        api.withdraw(accountId, "300.00").andExpect(status().isCreated());
        api.withdraw(accountId, "0.01")
                .andExpect(status().is(UNPROCESSABLE))
                .andExpect(jsonPath("$.remainingToday").value(0.00));

        assertThat(api.balanceOf(accountId)).isEqualTo(Money.of("4000.00"));
    }

    @Test
    void depositsDoNotRestoreTheDailyLimit() throws Exception {
        String accountId = api.openAccountWithBalance("Ada Lovelace", "2000.00");

        api.withdraw(accountId, "900.00").andExpect(status().isCreated());
        api.deposit(accountId, "5000.00").andExpect(status().isCreated());

        api.withdraw(accountId, "100.01").andExpect(status().is(UNPROCESSABLE));
        api.withdraw(accountId, "100.00").andExpect(status().isCreated());
    }

    @Test
    void dailyLimitResetsAtMidnightInTheBankTimeZone() throws Exception {
        clock.setInstant(localTime("2026-03-10T23:30"));
        String accountId = api.openAccountWithBalance("Ada Lovelace", "3000.00");
        api.withdraw(accountId, "1000.00").andExpect(status().isCreated());

        clock.setInstant(localTime("2026-03-10T23:59:59"));
        api.withdraw(accountId, "0.01").andExpect(status().is(UNPROCESSABLE));

        clock.setInstant(localTime("2026-03-11T00:00"));
        api.withdraw(accountId, "1000.00").andExpect(status().isCreated());
    }

    @Test
    void cannotWithdrawMoreThanTheBalance() throws Exception {
        String accountId = api.openAccountWithBalance("Ada Lovelace", "100.00");

        api.withdraw(accountId, "100.01")
                .andExpect(status().is(UNPROCESSABLE))
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"));

        assertThat(api.balanceOf(accountId)).isEqualTo(Money.of("100.00"));
    }

    @Test
    void retriedDepositWithTheSameIdempotencyKeyIsAppliedOnce() throws Exception {
        String accountId = api.openAccount("Ada Lovelace");

        String firstId = api.createdId(api.deposit(accountId, "2500.00", "salary-2026-03"));
        String retryId = api.createdId(api.deposit(accountId, "2500.00", "salary-2026-03"));

        assertThat(retryId).isEqualTo(firstId);
        assertThat(api.balanceOf(accountId)).isEqualTo(Money.of("2500.00"));
        api.transactions(accountId).andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void idempotencyKeyCannotBeReusedForADifferentAmount() throws Exception {
        String accountId = api.openAccount("Ada Lovelace");
        api.deposit(accountId, "100.00", "gift-001").andExpect(status().isCreated());

        api.deposit(accountId, "200.00", "gift-001")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

        assertThat(api.balanceOf(accountId)).isEqualTo(Money.of("100.00"));
    }

    @Test
    void unknownAccountIsNotFound() throws Exception {
        api.balance(UUID.randomUUID().toString())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));
    }

    @Test
    void readOnlyClientsCannotMoveMoney() throws Exception {
        String accountId = api.openAccount("Ada Lovelace");

        mvc.perform(post("/api/v1/accounts/{accountId}/deposits", accountId)
                        .with(withScopes(ACCOUNTS_READ))
                        .contentType(APPLICATION_JSON)
                        .content("{\"amount\": 10.00}"))
                .andExpect(status().isForbidden());

        assertThat(api.balanceOf(accountId)).isEqualTo(Money.of("0.00"));
    }

    private Instant localTime(String localDateTime) {
        return LocalDateTime.parse(localDateTime).atZone(properties.businessTimeZone()).toInstant();
    }
}
