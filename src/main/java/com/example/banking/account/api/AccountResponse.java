package com.example.banking.account.api;

import com.example.banking.account.Account;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record AccountResponse(UUID id, String ownerName, BigDecimal balance, Instant createdAt) {

    static AccountResponse from(Account account) {
        return new AccountResponse(account.getId(), account.getOwnerName(), account.getBalance(),
                account.getCreatedAt());
    }
}
