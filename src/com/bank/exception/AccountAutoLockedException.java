package com.bank.exception;

/**
 * Thrown when an account is automatically locked after too many failed PIN attempts.
 *
 * Folder: BankManagementSystem/src/com/bank/exception/AccountAutoLockedException.java
 *
 * Extends AccountLockedException so existing catch blocks still catch it,
 * but callers that need to show the specific "auto-locked" message can
 * catch this subtype first.
 */
public class AccountAutoLockedException extends AccountLockedException {

    private final int maxAttempts;

    public AccountAutoLockedException(String accountNumber, int maxAttempts) {
        super(accountNumber);
        this.maxAttempts = maxAttempts;
    }

    @Override
    public String getMessage() {
        return "Your account has been locked after " + maxAttempts
                + " consecutive wrong PIN attempts.\n"
                + "Use the Recover PIN tab to verify your national ID, card number, and registered phone,\n"
                + "or contact support for assistance.";
    }

    public int getMaxAttempts() { return maxAttempts; }
}
