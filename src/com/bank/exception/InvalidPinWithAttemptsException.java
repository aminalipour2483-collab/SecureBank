package com.bank.exception;

/**
 * Thrown when a PIN is wrong but the account is NOT yet locked.
 * Carries the attempt counts so the UI can show a live warning counter.
 *
 * Folder: BankManagementSystem/src/com/bank/exception/InvalidPinWithAttemptsException.java
 *
 * Extends InvalidPinException so existing catch (InvalidPinException) blocks
 * still catch it. Callers that need the attempt numbers catch this first.
 *
 * Example UI message:
 *   "Incorrect PIN.  Attempt 2 of 3.  1 attempt remaining before lockout."
 */
public class InvalidPinWithAttemptsException extends InvalidPinException {

    private final int attemptsMade;   // how many wrong attempts so far (1, 2 …)
    private final int maxAttempts;    // the ceiling before lockout (always 3)
    private final int attemptsLeft;   // maxAttempts - attemptsMade

    public InvalidPinWithAttemptsException(int attemptsMade,
                                           int maxAttempts,
                                           int attemptsLeft) {
        this.attemptsMade  = attemptsMade;
        this.maxAttempts   = maxAttempts;
        this.attemptsLeft  = attemptsLeft;
    }

    @Override
    public String getMessage() {
        return "Incorrect PIN.  Attempt " + attemptsMade + " of " + maxAttempts + ".  "
                + attemptsLeft + " attempt"
                + (attemptsLeft == 1 ? "" : "s")
                + " remaining before your account is locked.";
    }

    public int getAttemptsMade()  { return attemptsMade; }
    public int getMaxAttempts()   { return maxAttempts; }
    public int getAttemptsLeft()  { return attemptsLeft; }

    /** True when the very next wrong attempt will lock the account. */
    public boolean isLastAttempt() { return attemptsLeft == 1; }
}
