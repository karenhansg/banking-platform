package com.example.banking.support;

import com.example.banking.config.BankingProperties;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.ZoneId;

public final class TestProperties {

    private TestProperties() {
    }

    public static BankingProperties banking(ZoneId businessTimeZone, String dailyWithdrawalLimit,
            Duration acceptanceWindow) {
        return new BankingProperties(businessTimeZone, new BigDecimal(dailyWithdrawalLimit),
                new BankingProperties.Payments(acceptanceWindow, false, Duration.ofSeconds(15), 100));
    }
}
