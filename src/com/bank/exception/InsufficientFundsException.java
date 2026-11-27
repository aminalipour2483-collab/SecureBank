package com.bank.exception;

import java.math.BigDecimal;

/**
 * Thrown when a withdrawal or transfer amount exceeds the account balance.
 *
 * Folder: BankManagementSystem/src/com/bank/exception/InsufficientFundsException.java
 */
public class InsufficientFundsException extends BankException {

    private final BigDecimal available;
    private final BigDecimal requested;

    public InsufficientFundsException(BigDecimal available, BigDecimal requested) {
        super(String.format(
            "Insufficient funds. Available: $%.2f, Requested: $%.2f",
            available, requested));
        this.available = available;
        this.requested = requested;
    }

    public BigDecimal getAvailable() { return available; }
    public BigDecimal getRequested() { return requested; }
}
