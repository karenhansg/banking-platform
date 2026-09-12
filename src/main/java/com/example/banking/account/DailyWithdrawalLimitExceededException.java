package com.example.banking.account;

import com.example.banking.common.BusinessException;
import com.example.banking.common.ErrorCode;
import java.math.BigDecimal;
import java.util.Map;

public class DailyWithdrawalLimitExceededException extends BusinessException {

    private final BigDecimal dailyLimit;
    private final BigDecimal remainingToday;
    private final BigDecimal requestedAmount;

    public DailyWithdrawalLimitExceededException(BigDecimal dailyLimit, BigDecimal remainingToday,
            BigDecimal requestedAmount) {
        super(ErrorCode.DAILY_WITHDRAWAL_LIMIT_EXCEEDED,
                ("Requested amount %s exceeds your remaining daily withdrawal limit of %s (daily limit: %s). "
                        + "Please contact the bank for assistance.")
                        .formatted(requestedAmount, remainingToday, dailyLimit));
        this.dailyLimit = dailyLimit;
        this.remainingToday = remainingToday;
        this.requestedAmount = requestedAmount;
    }

    @Override
    public Map<String, Object> details() {
        return Map.of(
                "dailyLimit", dailyLimit,
                "remainingToday", remainingToday,
                "requestedAmount", requestedAmount);
    }
}
