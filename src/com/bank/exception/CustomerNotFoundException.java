package com.bank.exception;

/**
 * Thrown when a customer lookup returns no result.
 *
 * Folder: BankManagementSystem/src/com/bank/exception/CustomerNotFoundException.java
 */
public class CustomerNotFoundException extends BankException {

    public CustomerNotFoundException(String identifier) {
        super("Customer not found: " + identifier);
    }
}
