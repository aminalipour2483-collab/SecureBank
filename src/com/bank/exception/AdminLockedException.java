package com.bank.exception;

/**
 * Thrown when an account has been manually locked by an administrator.
 * This is a permanent lock that can only be removed by an admin.
 */
public class AdminLockedException extends BankException {

    public AdminLockedException() {
        super("Your account has been locked by the system administrator. Please contact support.");
    }

    public AdminLockedException(String accountNumber) {
        super("Account " + accountNumber + " has been locked by the administrator. Please contact support.");
    }
}