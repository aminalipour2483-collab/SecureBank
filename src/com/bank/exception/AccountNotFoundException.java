package com.bank.exception;

/**
 * Thrown when a lookup for an account returns no result.
 *
 * Folder: BankManagementSystem/src/com/bank/exception/AccountNotFoundException.java
 */
public class AccountNotFoundException extends BankException {

    public AccountNotFoundException(String identifier) {
        super("Account not found: " + identifier);
    }
}
