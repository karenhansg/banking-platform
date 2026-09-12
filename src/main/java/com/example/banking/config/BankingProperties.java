package com.example.banking.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Business rules that vary by deployment.
 *
 * @param businessTimeZone     time zone that defines a banking day; the daily withdrawal limit resets at midnight here
 * @param dailyWithdrawalLimit maximum an account may withdraw per banking day, including executed scheduled payments
 * @param payments             scheduled payment settings
 */
@Validated
@ConfigurationProperties("banking")
public record BankingProperties(
        @DefaultValue("UTC") @NotNull ZoneId businessTimeZone,
        @DefaultValue("1000.00") @NotNull @Positive BigDecimal dailyWithdrawalLimit,
        @DefaultValue @NotNull @Valid Payments payments) {

    /**
     * @param acceptanceWindow   how long held funds wait for the target account to accept before they are returned
     * @param schedulerEnabled   whether this instance runs the background job that executes and expires payments
     * @param schedulerInterval  delay between background job runs
     * @param schedulerBatchSize maximum payments expired, and separately executed, per job run
     */
    public record Payments(
            @DefaultValue("P3D") @NotNull Duration acceptanceWindow,
            @DefaultValue("true") boolean schedulerEnabled,
            @DefaultValue("PT15S") @NotNull Duration schedulerInterval,
            @DefaultValue("100") @Positive int schedulerBatchSize) {
    }
}
