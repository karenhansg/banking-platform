package com.example.banking.support;

import static com.example.banking.support.TestJwt.fullAccess;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.banking.common.Money;
import com.example.banking.common.web.IdempotencyKey;
import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Drives the HTTP API in integration tests as a client with full access. */
public class BankingApi {

    private final MockMvc mvc;

    public BankingApi(MockMvc mvc) {
        this.mvc = mvc;
    }

    public String openAccount(String ownerName) throws Exception {
        return createdId(mvc.perform(post("/api/v1/accounts")
                .with(fullAccess())
                .contentType(APPLICATION_JSON)
                .content("{\"ownerName\": \"%s\"}".formatted(ownerName))));
    }

    public String openAccountWithBalance(String ownerName, String balance) throws Exception {
        String accountId = openAccount(ownerName);
        deposit(accountId, balance).andExpect(status().isCreated());
        return accountId;
    }

    public ResultActions deposit(String accountId, String amount) throws Exception {
        return deposit(accountId, amount, null);
    }

    public ResultActions deposit(String accountId, String amount, @Nullable String idempotencyKey) throws Exception {
        return moveMoney(accountId, "deposits", amount, idempotencyKey);
    }

    public ResultActions withdraw(String accountId, String amount) throws Exception {
        return moveMoney(accountId, "withdrawals", amount, null);
    }

    public ResultActions balance(String accountId) throws Exception {
        return mvc.perform(get("/api/v1/accounts/{accountId}/balance", accountId).with(fullAccess()));
    }

    public BigDecimal balanceOf(String accountId) throws Exception {
        String body = balance(accountId).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        Object balance = JsonPath.read(body, "$.balance");
        return Money.of(new BigDecimal(balance.toString()));
    }

    public ResultActions transactions(String accountId) throws Exception {
        return mvc.perform(get("/api/v1/accounts/{accountId}/transactions", accountId).with(fullAccess()));
    }

    public ResultActions schedulePayment(String sourceAccountId, String targetAccountId, String amount,
            @Nullable Instant executeAt, @Nullable String idempotencyKey) throws Exception {
        String executeAtJson = executeAt == null ? "null" : "\"" + executeAt + "\"";
        MockHttpServletRequestBuilder request = post("/api/v1/payments")
                .with(fullAccess())
                .contentType(APPLICATION_JSON)
                .content("""
                        {"sourceAccountId": "%s", "targetAccountId": "%s", "amount": %s, "executeAt": %s}
                        """.formatted(sourceAccountId, targetAccountId, amount, executeAtJson));
        if (idempotencyKey != null) {
            request.header(IdempotencyKey.HEADER, idempotencyKey);
        }
        return mvc.perform(request);
    }

    public ResultActions payment(String paymentId) throws Exception {
        return mvc.perform(get("/api/v1/payments/{paymentId}", paymentId).with(fullAccess()));
    }

    public ResultActions paymentsOf(String accountId, @Nullable String status) throws Exception {
        MockHttpServletRequestBuilder request = get("/api/v1/payments").param("accountId", accountId).with(fullAccess());
        if (status != null) {
            request.param("status", status);
        }
        return mvc.perform(request);
    }

    public ResultActions acceptPayment(String paymentId) throws Exception {
        return mvc.perform(post("/api/v1/payments/{paymentId}/accept", paymentId).with(fullAccess()));
    }

    public ResultActions cancelPayment(String paymentId) throws Exception {
        return mvc.perform(post("/api/v1/payments/{paymentId}/cancel", paymentId).with(fullAccess()));
    }

    /** Asserts that the request created a resource and returns its id. */
    public String createdId(ResultActions result) throws Exception {
        String body = result.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    private ResultActions moveMoney(String accountId, String operation, String amount,
            @Nullable String idempotencyKey) throws Exception {
        MockHttpServletRequestBuilder request = post("/api/v1/accounts/{accountId}/{operation}", accountId, operation)
                .with(fullAccess())
                .contentType(APPLICATION_JSON)
                .content("{\"amount\": %s}".formatted(amount));
        if (idempotencyKey != null) {
            request.header(IdempotencyKey.HEADER, idempotencyKey);
        }
        return mvc.perform(request);
    }
}
