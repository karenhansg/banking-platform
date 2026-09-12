package com.example.banking.common;

import java.util.Map;

/** A request that breaks a business rule. The message is written for, and safe to show to, API clients. */
public abstract class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    protected BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    /** Additional machine-readable fields included in the API problem response. */
    public Map<String, Object> details() {
        return Map.of();
    }
}
