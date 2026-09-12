package com.example.banking.common.web;

/**
 * Clients may send an {@value #HEADER} header with money-moving requests. Retrying a request with the same key returns
 * the original result instead of moving the money a second time, so clients can retry safely after a timeout.
 */
public final class IdempotencyKey {

    public static final String HEADER = "Idempotency-Key";
    public static final String PATTERN = "[A-Za-z0-9_.:-]{1,64}";

    private IdempotencyKey() {
    }
}
