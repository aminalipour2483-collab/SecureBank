package com.bank.exception;

/**
 * Thrown when a PIN verification attempt fails.
 *
 * Folder: BankManagementSystem/src/com/bank/exception/InvalidPinException.java
 */
public class InvalidPinException extends BankException {

    public InvalidPinException() {
        super("Incorrect PIN. Please try again.");
    }
}
