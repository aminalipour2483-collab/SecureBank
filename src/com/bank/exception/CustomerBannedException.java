package com.bank.exception;

/**
 * CustomerBannedException — thrown when a BANNED customer tries to log in
 * or move money.                                         (v3.0 — NEW)
 *
 * Folder: BankManagementSystem/src/com/bank/exception/CustomerBannedException.java
 *
 * Banning is done by an administrator from the Fraud Management panel after
 * a fraud alert is confirmed. The reason recorded with the ban is carried
 * in the exception message so the Swing UI can show it to the customer.
 */
public class CustomerBannedException extends BankException {

    private final String customerName;
    private final String reason;

    public CustomerBannedException(String customerName, String reason) {
        super(buildMessage(customerName, reason));
        this.customerName = customerName;
        this.reason       = reason;
    }

    private static String buildMessage(String customerName, String reason) {
        String base = "Your access has been banned by the bank administration";
        if (reason != null && !reason.isBlank()) {
            base += ". Reason: " + reason.trim();
        }
        base += ". Please contact bank support.";
        return base;
    }

    public String getCustomerName() { return customerName; }
    public String getReason()       { return reason; }
}
