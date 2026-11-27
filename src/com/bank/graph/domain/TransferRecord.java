package com.bank.graph.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** One individual transfer (any status), shown when an administrator drills into customers. */
public record TransferRecord(long transferId,
                             CustomerIdentity sender,
                             CustomerIdentity receiver,
                             BigDecimal amount,
                             String referenceNumber,
                             String status,
                             LocalDateTime createdAt) {

    public boolean isCompleted() {
        return "COMPLETED".equalsIgnoreCase(status);
    }
}
