package com.bank.fraud.outbox;
/** Read-only administrative projection of an outbox event and any result. */
public record FraudEventSummary(long id, long accountId, String type, String status, int attempt,
        int cycle, Integer score, String risk, String rules, String details, String error) {}
