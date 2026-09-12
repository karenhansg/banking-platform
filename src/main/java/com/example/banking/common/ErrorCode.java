package com.example.banking.common;

/** Stable, machine-readable error codes returned in the {@code code} field of API problem responses. */
public enum ErrorCode {

    VALIDATION_FAILED("Request validation failed"),
    MALFORMED_REQUEST("Malformed request"),
    ACCOUNT_NOT_FOUND("Account not found"),
    PAYMENT_NOT_FOUND("Payment not found"),
    INSUFFICIENT_FUNDS("Insufficient funds"),
    DAILY_WITHDRAWAL_LIMIT_EXCEEDED("Daily withdrawal limit exceeded"),
    IDEMPOTENCY_KEY_REUSED("Idempotency key reused"),
    INVALID_PAYMENT_REQUEST("Invalid payment request"),
    INVALID_PAYMENT_STATE("Invalid payment state"),
    PAYMENT_EXPIRED("Payment expired"),
    CONCURRENT_MODIFICATION("Concurrent modification"),
    INTERNAL_ERROR("Internal error");

    private final String title;

    ErrorCode(String title) {
        this.title = title;
    }

    /** Short, human-readable summary of the problem type. */
    public String title() {
        return title;
    }
}
