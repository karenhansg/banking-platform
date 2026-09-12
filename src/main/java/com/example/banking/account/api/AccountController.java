package com.example.banking.account.api;

import com.example.banking.account.Account;
import com.example.banking.account.AccountBalance;
import com.example.banking.account.AccountService;
import com.example.banking.common.web.IdempotencyKey;
import com.example.banking.common.web.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import java.net.URI;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

@RestController
@RequestMapping("/api/v1/accounts")
@Tag(name = "Accounts")
public class AccountController {

    static final int MAX_PAGE_SIZE = 100;

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @PostMapping
    @Operation(summary = "Open an account with a zero balance")
    public ResponseEntity<AccountResponse> openAccount(@Valid @RequestBody OpenAccountRequest request) {
        Account account = accountService.openAccount(request.ownerName());
        URI location = UriComponentsBuilder.fromPath("/api/v1/accounts/{accountId}")
                .buildAndExpand(account.getId())
                .toUri();
        return ResponseEntity.created(location).body(AccountResponse.from(account));
    }

    @GetMapping("/{accountId}")
    @Operation(summary = "Get an account")
    public AccountResponse getAccount(@PathVariable UUID accountId) {
        return AccountResponse.from(accountService.getAccount(accountId));
    }

    @GetMapping("/{accountId}/balance")
    @Operation(summary = "Get the current balance and what remains of today's withdrawal limit")
    public AccountBalance getBalance(@PathVariable UUID accountId) {
        return accountService.getBalance(accountId);
    }

    @PostMapping("/{accountId}/deposits")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Deposit money into an account")
    public TransactionResponse deposit(
            @PathVariable UUID accountId,
            @Valid @RequestBody MoneyRequest request,
            @RequestHeader(name = IdempotencyKey.HEADER, required = false)
            @Pattern(regexp = IdempotencyKey.PATTERN) @Nullable String idempotencyKey) {
        return TransactionResponse.from(
                accountService.deposit(accountId, request.amount(), request.description(), idempotencyKey));
    }

    @PostMapping("/{accountId}/withdrawals")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
            summary = "Withdraw money from an account",
            description = "Fails with 422 DAILY_WITHDRAWAL_LIMIT_EXCEEDED if today's withdrawals, including executed "
                    + "scheduled payments, would exceed the daily limit, and with 422 INSUFFICIENT_FUNDS if the "
                    + "amount exceeds the balance.")
    public TransactionResponse withdraw(
            @PathVariable UUID accountId,
            @Valid @RequestBody MoneyRequest request,
            @RequestHeader(name = IdempotencyKey.HEADER, required = false)
            @Pattern(regexp = IdempotencyKey.PATTERN) @Nullable String idempotencyKey) {
        return TransactionResponse.from(
                accountService.withdraw(accountId, request.amount(), request.description(), idempotencyKey));
    }

    @GetMapping("/{accountId}/transactions")
    @Operation(summary = "List an account's transactions, newest first")
    public PageResponse<TransactionResponse> getTransactions(
            @PathVariable UUID accountId,
            @RequestParam(defaultValue = "0") @PositiveOrZero int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int size) {
        PageRequest pageRequest = PageRequest.of(page, size, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        return PageResponse.of(accountService.getTransactions(accountId, pageRequest), TransactionResponse::from);
    }
}
