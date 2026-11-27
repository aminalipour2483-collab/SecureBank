package com.bank.exception;

/** Raised when an archived customer attempts to authenticate or use banking services. */
public final class CustomerArchivedException extends BankException {
    public CustomerArchivedException(String customerName) {
        super("Customer " + (customerName == null ? "" : customerName)
                + " is archived. Access and financial operations are disabled. Please contact an administrator.");
    }
}
