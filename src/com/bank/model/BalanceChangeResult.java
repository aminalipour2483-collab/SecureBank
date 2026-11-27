package com.bank.model;

import java.math.BigDecimal;

/** Result of an atomic deposit or withdrawal, including whether it was replayed. */
public final class BalanceChangeResult {

    private final long customerId;
    private final BigDecimal balanceBefore;
    private final BigDecimal balanceAfter;
    private final long transactionId;
    private final boolean replayed;

    public BalanceChangeResult(long customerId, BigDecimal balanceBefore,
                               BigDecimal balanceAfter, long transactionId) {
        this(customerId, balanceBefore, balanceAfter, transactionId, false);
    }

    public BalanceChangeResult(long customerId, BigDecimal balanceBefore,
                               BigDecimal balanceAfter, long transactionId,
                               boolean replayed) {
        this.customerId = customerId;
        this.balanceBefore = balanceBefore;
        this.balanceAfter = balanceAfter;
        this.transactionId = transactionId;
        this.replayed = replayed;
    }

    public long getCustomerId() { return customerId; }
    public BigDecimal getBalanceBefore() { return balanceBefore; }
    public BigDecimal getBalanceAfter() { return balanceAfter; }
    public long getTransactionId() { return transactionId; }
    public boolean isReplayed() { return replayed; }
}
