package com.example.banking.account;

import com.example.banking.common.BusinessException;
import com.example.banking.common.ErrorCode;
import java.util.UUID;

public class AccountNotFoundException extends BusinessException {

    public AccountNotFoundException(UUID accountId) {
        super(ErrorCode.ACCOUNT_NOT_FOUND, "Account %s was not found".formatted(accountId));
    }
}
