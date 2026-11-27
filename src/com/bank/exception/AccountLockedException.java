package com.bank.exception;

/**
 * Thrown when an operation is attempted on a LOCKED account.
 *
 * Folder: BankManagementSystem/src/com/bank/exception/AccountLockedException.java
 */
public class AccountLockedException extends BankException {

    public AccountLockedException(String accountNumber) {
        super("Account " + accountNumber + " is locked. Please contact support.");
    }
}
