package com.example.banking.common;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/** Monetary amounts are {@link BigDecimal}s with exactly two decimal places, in the bank's single currency. */
public final class Money {

    public static final int SCALE = 2;
    public static final BigDecimal ZERO = BigDecimal.ZERO.setScale(SCALE);

    private Money() {
    }

    /** Returns the amount with two decimal places, rejecting amounts that would need rounding. */
    public static BigDecimal of(BigDecimal amount) {
        Objects.requireNonNull(amount, "amount must not be null");
        try {
            return amount.setScale(SCALE, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("Amount must have at most " + SCALE + " decimal places: " + amount, e);
        }
    }

    public static BigDecimal of(String amount) {
        return of(new BigDecimal(amount));
    }

    /** Returns the amount with two decimal places, rejecting zero, negative and over-precise amounts. */
    public static BigDecimal positive(BigDecimal amount) {
        BigDecimal money = of(amount);
        if (money.signum() <= 0) {
            throw new IllegalArgumentException("Amount must be positive: " + amount);
        }
        return money;
    }
}
