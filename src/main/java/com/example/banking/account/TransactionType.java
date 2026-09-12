package com.example.banking.account;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/** Kinds of ledger entry. Debits reduce the balance, and some debits count towards the daily withdrawal limit. */
public enum TransactionType {

    DEPOSIT(Direction.CREDIT, false),
    WITHDRAWAL(Direction.DEBIT, true),
    /** Funds taken from the source account when a scheduled payment executes. */
    PAYMENT_HOLD(Direction.DEBIT, true),
    /** Held funds returned to the source account because the payment expired unaccepted. */
    PAYMENT_REFUND(Direction.CREDIT, false),
    /** Held funds credited to the target account when it accepts the payment. */
    PAYMENT_RECEIVED(Direction.CREDIT, false);

    private enum Direction { CREDIT, DEBIT }

    private final Direction direction;
    private final boolean countsTowardsDailyWithdrawalLimit;

    TransactionType(Direction direction, boolean countsTowardsDailyWithdrawalLimit) {
        this.direction = direction;
        this.countsTowardsDailyWithdrawalLimit = countsTowardsDailyWithdrawalLimit;
    }

    public boolean isDebit() {
        return direction == Direction.DEBIT;
    }

    public boolean countsTowardsDailyWithdrawalLimit() {
        return countsTowardsDailyWithdrawalLimit;
    }

    static Set<TransactionType> countingTowardsDailyWithdrawalLimit() {
        return Arrays.stream(values())
                .filter(TransactionType::countsTowardsDailyWithdrawalLimit)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(TransactionType.class)));
    }
}
