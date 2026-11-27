package com.bank.graph.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;

/** Source row: all COMPLETED transfers from one customer to another, already summed. */
public record TransferFlow(CustomerIdentity sender,
                           CustomerIdentity receiver,
                           long transferCount,
                           BigDecimal totalAmount,
                           LocalDateTime lastActivity) {

    public TransferFlow {
        Objects.requireNonNull(sender, "sender");
        Objects.requireNonNull(receiver, "receiver");
        Objects.requireNonNull(totalAmount, "totalAmount");
        if (transferCount < 0) throw new IllegalArgumentException("transferCount must not be negative");
    }
}
