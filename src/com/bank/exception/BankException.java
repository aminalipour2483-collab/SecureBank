package com.bank.exception;

/**
 * BankException — base class for all custom bank business exceptions.
 *
 * Folder: BankManagementSystem/src/com/bank/exception/BankException.java
 *
 * By extending RuntimeException, these exceptions are "unchecked" —
 * the Service layer throws them freely and the Swing UI catches them
 * in one place to display a dialog, without every method needing
 * a 'throws' clause in its signature.
 */
public class BankException extends RuntimeException {
    public BankException(String message) {
        super(message);
    }
    public BankException(String message, Throwable cause) {
        super(message, cause);
    }
}
