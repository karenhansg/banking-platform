package com.example.banking.payment;

/**
 * Lifecycle of a scheduled payment.
 *
 * <pre>
 * SCHEDULED ──execute──▶ PENDING_ACCEPTANCE ──accept──▶ COMPLETED
 *     │                         │
 *     ├──cancel──▶ CANCELLED    └──expire──▶ EXPIRED (held funds returned)
 *     └──limit/funds──▶ FAILED
 * </pre>
 */
public enum PaymentStatus {
    /** Waiting for its execution time; no money has moved. */
    SCHEDULED,
    /** Funds are held from the source account until the target accepts or the hold expires. */
    PENDING_ACCEPTANCE,
    /** The target accepted and was credited. */
    COMPLETED,
    /** The target did not accept in time; the held funds went back to the source account. */
    EXPIRED,
    /** Cancelled before execution. */
    CANCELLED,
    /** Could not execute (insufficient funds or daily withdrawal limit reached). */
    FAILED
}
