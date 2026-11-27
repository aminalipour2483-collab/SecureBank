package com.bank.model;

import java.math.BigDecimal;

/** Values captured while the transfer's account rows are locked, or replayed from idempotency storage. */
public final class TransferExecutionResult {

    private final long transferId;
    private final long senderCustomerId;
    private final BigDecimal senderBalanceBefore;
    private final boolean replayed;

    public TransferExecutionResult(long transferId, long senderCustomerId,
                                   BigDecimal senderBalanceBefore) {
        this(transferId, senderCustomerId, senderBalanceBefore, false);
    }

    public TransferExecutionResult(long transferId, long senderCustomerId,
                                   BigDecimal senderBalanceBefore, boolean replayed) {
        this.transferId = transferId;
        this.senderCustomerId = senderCustomerId;
        this.senderBalanceBefore = senderBalanceBefore;
        this.replayed = replayed;
    }

    public long getTransferId() { return transferId; }
    public long getSenderCustomerId() { return senderCustomerId; }
    public BigDecimal getSenderBalanceBefore() { return senderBalanceBefore; }
    public boolean isReplayed() { return replayed; }
}
