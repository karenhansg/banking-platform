package com.example.banking.account;

import com.example.banking.common.BusinessException;
import com.example.banking.common.ErrorCode;
import java.math.BigDecimal;
import java.util.Map;

public class InsufficientFundsException extends BusinessException {

    private final BigDecimal availableBalance;
    private final BigDecimal requestedAmount;

    public InsufficientFundsException(BigDecimal availableBalance, BigDecimal requestedAmount) {
        super(ErrorCode.INSUFFICIENT_FUNDS, "Insufficient funds: requested %s but the available balance is %s"
                .formatted(requestedAmount, availableBalance));
        this.availableBalance = availableBalance;
        this.requestedAmount = requestedAmount;
    }

    @Override
    public Map<String, Object> details() {
        return Map.of("availableBalance", availableBalance, "requestedAmount", requestedAmount);
    }
}
