package com.example.banking.account.api;

import static com.example.banking.config.Scopes.ACCOUNTS_READ;
import static com.example.banking.config.Scopes.ACCOUNTS_WRITE;
import static com.example.banking.support.TestJwt.withScopes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.banking.account.Account;
import com.example.banking.account.AccountBalance;
import com.example.banking.account.AccountFixtures;
import com.example.banking.account.AccountNotFoundException;
import com.example.banking.account.AccountService;
import com.example.banking.account.AccountTransaction;
import com.example.banking.account.DailyWithdrawalLimitExceededException;
import com.example.banking.account.InsufficientFundsException;
import com.example.banking.account.Posting;
import com.example.banking.common.IdempotencyKeyReusedException;
import com.example.banking.common.Money;
import com.example.banking.common.web.IdempotencyKey;
import com.example.banking.common.web.RequestIdFilter;
import com.example.banking.config.SecurityConfiguration;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@WebMvcTest(AccountController.class)
@Import(SecurityConfiguration.class)
class AccountControllerTest {

    private static final UUID ACCOUNT_ID = UUID.fromString("0b6f1f5e-8a8e-4c4b-9d3e-5a4f2b1c7d90");
    private static final Instant NOW = Instant.parse("2026-03-10T14:00:00Z");

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private AccountService accountService;

    /** Tokens are simulated with jwt(); the resource server only needs a decoder to exist. */
    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void opensAnAccount() throws Exception {
        when(accountService.openAccount("Ada Lovelace"))
                .thenReturn(AccountFixtures.account(ACCOUNT_ID, "Ada Lovelace", "0.00", NOW));

        mvc.perform(post("/api/v1/accounts")
                        .with(withScopes(ACCOUNTS_WRITE))
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"ownerName": "Ada Lovelace"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/accounts/" + ACCOUNT_ID))
                .andExpect(jsonPath("$.id").value(ACCOUNT_ID.toString()))
                .andExpect(jsonPath("$.ownerName").value("Ada Lovelace"))
                .andExpect(jsonPath("$.balance").value(0.00));
    }

    @Test
    void rejectsABlankOwnerName() throws Exception {
        mvc.perform(post("/api/v1/accounts")
                        .with(withScopes(ACCOUNTS_WRITE))
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"ownerName": " "}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("ownerName"));

        verifyNoInteractions(accountService);
    }

    @Test
    void returnsTheBalanceWithWhatRemainsOfTodaysLimit() throws Exception {
        when(accountService.getBalance(ACCOUNT_ID)).thenReturn(
                new AccountBalance(ACCOUNT_ID, Money.of("1250.50"), Money.of("1000.00"), Money.of("300.00"), NOW));

        mvc.perform(get("/api/v1/accounts/{accountId}/balance", ACCOUNT_ID).with(withScopes(ACCOUNTS_READ)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value(ACCOUNT_ID.toString()))
                .andExpect(jsonPath("$.balance").value(1250.50))
                .andExpect(jsonPath("$.dailyWithdrawalLimit").value(1000.00))
                .andExpect(jsonPath("$.remainingDailyWithdrawalLimit").value(300.00))
                .andExpect(jsonPath("$.asOf").value("2026-03-10T14:00:00Z"));
    }

    @Test
    void unknownAccountIsNotFoundAndTheErrorCarriesTheRequestId() throws Exception {
        when(accountService.getBalance(ACCOUNT_ID)).thenThrow(new AccountNotFoundException(ACCOUNT_ID));

        mvc.perform(get("/api/v1/accounts/{accountId}/balance", ACCOUNT_ID)
                        .with(withScopes(ACCOUNTS_READ))
                        .header(RequestIdFilter.HEADER, "support-case-7"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"))
                .andExpect(jsonPath("$.title").value("Account not found"))
                .andExpect(jsonPath("$.requestId").value("support-case-7"))
                .andExpect(header().string(RequestIdFilter.HEADER, "support-case-7"));
    }

    @Test
    void malformedAccountIdIsABadRequest() throws Exception {
        mvc.perform(get("/api/v1/accounts/not-a-uuid/balance").with(withScopes(ACCOUNTS_READ)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void depositsMoney() throws Exception {
        Account account = AccountFixtures.account(ACCOUNT_ID, "Ada Lovelace", "100.50", NOW);
        AccountTransaction deposit = AccountFixtures.transaction(account,
                Posting.deposit(Money.of("100.50"), "Salary", "salary-2026-03"), NOW);
        when(accountService.deposit(ACCOUNT_ID, new BigDecimal("100.50"), "Salary", "salary-2026-03"))
                .thenReturn(deposit);

        mvc.perform(post("/api/v1/accounts/{accountId}/deposits", ACCOUNT_ID)
                        .with(withScopes(ACCOUNTS_WRITE))
                        .header(IdempotencyKey.HEADER, "salary-2026-03")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"amount": 100.50, "description": "Salary"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(deposit.getId().toString()))
                .andExpect(jsonPath("$.type").value("DEPOSIT"))
                .andExpect(jsonPath("$.amount").value(100.50))
                .andExpect(jsonPath("$.balanceAfter").value(100.50))
                .andExpect(jsonPath("$.description").value("Salary"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "0.00", "-5.00", "10.001", "12345678901234.00", "null"})
    void rejectsInvalidAmounts(String amount) throws Exception {
        mvc.perform(withdrawal("{\"amount\": %s}".formatted(amount)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("amount"));

        verifyNoInteractions(accountService);
    }

    @Test
    void rejectsABodyThatIsNotJson() throws Exception {
        mvc.perform(withdrawal("{\"amount\": "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    void rejectsAnInvalidIdempotencyKey() throws Exception {
        mvc.perform(withdrawal("{\"amount\": 10.00}").header(IdempotencyKey.HEADER, "not a valid key!"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("idempotencyKey"));

        verifyNoInteractions(accountService);
    }

    @Test
    void withdrawalOverTheDailyLimitAsksTheCustomerToContactTheBank() throws Exception {
        when(accountService.withdraw(ACCOUNT_ID, new BigDecimal("400.00"), null, null)).thenThrow(
                new DailyWithdrawalLimitExceededException(Money.of("1000.00"), Money.of("300.00"), Money.of("400.00")));

        mvc.perform(withdrawal("{\"amount\": 400.00}"))
                .andExpect(status().is(HttpStatus.UNPROCESSABLE_CONTENT.value()))
                .andExpect(content().contentType(APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("DAILY_WITHDRAWAL_LIMIT_EXCEEDED"))
                .andExpect(jsonPath("$.detail").value(containsString("Please contact the bank for assistance")))
                .andExpect(jsonPath("$.dailyLimit").value(1000.00))
                .andExpect(jsonPath("$.remainingToday").value(300.00))
                .andExpect(jsonPath("$.requestedAmount").value(400.00));
    }

    @Test
    void withdrawalBeyondTheBalanceIsRejected() throws Exception {
        when(accountService.withdraw(ACCOUNT_ID, new BigDecimal("400.00"), null, null))
                .thenThrow(new InsufficientFundsException(Money.of("100.00"), Money.of("400.00")));

        mvc.perform(withdrawal("{\"amount\": 400.00}"))
                .andExpect(status().is(HttpStatus.UNPROCESSABLE_CONTENT.value()))
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"))
                .andExpect(jsonPath("$.availableBalance").value(100.00));
    }

    @Test
    void reusingAnIdempotencyKeyForADifferentRequestIsAConflict() throws Exception {
        when(accountService.withdraw(ACCOUNT_ID, new BigDecimal("50.00"), null, "atm-0042"))
                .thenThrow(new IdempotencyKeyReusedException("atm-0042"));

        mvc.perform(withdrawal("{\"amount\": 50.00}").header(IdempotencyKey.HEADER, "atm-0042"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void lockContentionAsksTheClientToRetry() throws Exception {
        when(accountService.withdraw(any(), any(), any(), any()))
                .thenThrow(new PessimisticLockingFailureException("canceling statement due to lock timeout"));

        mvc.perform(withdrawal("{\"amount\": 50.00}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION"));
    }

    @Test
    void unexpectedErrorsDoNotLeakInternals() throws Exception {
        when(accountService.getBalance(ACCOUNT_ID))
                .thenThrow(new IllegalStateException("jdbc:postgresql://db.internal password=hunter2"));

        mvc.perform(get("/api/v1/accounts/{accountId}/balance", ACCOUNT_ID).with(withScopes(ACCOUNTS_READ)))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(content().string(not(containsString("hunter2"))));
    }

    @Test
    void listsTransactionsNewestFirst() throws Exception {
        Account account = AccountFixtures.account(ACCOUNT_ID, "Ada Lovelace", "100.00", NOW);
        AccountTransaction deposit =
                AccountFixtures.transaction(account, Posting.deposit(Money.of("100.00"), null, null), NOW);
        when(accountService.getTransactions(eq(ACCOUNT_ID), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(deposit), PageRequest.of(1, 5), 6));

        mvc.perform(get("/api/v1/accounts/{accountId}/transactions", ACCOUNT_ID)
                        .param("page", "1")
                        .param("size", "5")
                        .with(withScopes(ACCOUNTS_READ)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].type").value("DEPOSIT"))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(5))
                .andExpect(jsonPath("$.totalElements").value(6))
                .andExpect(jsonPath("$.totalPages").value(2));

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(accountService).getTransactions(eq(ACCOUNT_ID), pageable.capture());
        Sort.Order createdAt = pageable.getValue().getSort().getOrderFor("createdAt");
        assertThat(createdAt).isNotNull();
        assertThat(createdAt.isDescending()).isTrue();
    }

    @Test
    void pageSizeIsCapped() throws Exception {
        mvc.perform(get("/api/v1/accounts/{accountId}/transactions", ACCOUNT_ID)
                        .param("size", String.valueOf(AccountController.MAX_PAGE_SIZE + 1))
                        .with(withScopes(ACCOUNTS_READ)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("size"));
    }

    @Test
    void requiresAnAccessToken() throws Exception {
        mvc.perform(get("/api/v1/accounts/{accountId}/balance", ACCOUNT_ID))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(accountService);
    }

    @Test
    void readOnlyClientsCannotMoveMoney() throws Exception {
        mvc.perform(post("/api/v1/accounts/{accountId}/deposits", ACCOUNT_ID)
                        .with(withScopes(ACCOUNTS_READ))
                        .contentType(APPLICATION_JSON)
                        .content("{\"amount\": 10.00}"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(accountService);
    }

    private static MockHttpServletRequestBuilder withdrawal(String body) {
        return post("/api/v1/accounts/{accountId}/withdrawals", ACCOUNT_ID)
                .with(withScopes(ACCOUNTS_WRITE))
                .contentType(APPLICATION_JSON)
                .content(body);
    }
}
