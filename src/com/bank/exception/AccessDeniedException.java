package com.bank.exception;

/**
 * Thrown when a caller invokes an operation it is not authorised to perform
 * (for example, a missing or non-administrator principal).
 */
public class AccessDeniedException extends BankException {
    public AccessDeniedException(String message) {
        super(message);
    }
}
