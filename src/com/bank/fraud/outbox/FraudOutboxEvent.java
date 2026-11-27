package com.bank.fraud.outbox;

import com.bank.model.Transaction.TransactionType;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Immutable committed financial event awaiting fraud analysis. */
public record FraudOutboxEvent(long outboxId, long accountId, long customerId,
        TransactionType transactionType, BigDecimal amount, BigDecimal balanceBefore,
        Long transactionId, Long transferId, LocalDateTime occurredAt,
        int attemptCount, int retryCycle) { }
