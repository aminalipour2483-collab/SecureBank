package com.bank.security;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Proof of a successful customer authentication in this desktop application.
 * Instances are issued only by CustomerSessionService.
 *
 * This is an in-process application identity, not a remote security boundary.
 */
public final class CustomerPrincipal {
    private final long customerId;
    private final long authenticatedAccountId;
    private final UUID sessionId;
    private final Instant authenticatedAt;

    CustomerPrincipal(long customerId, long authenticatedAccountId, UUID sessionId, Instant authenticatedAt) {
        if (customerId <= 0 || authenticatedAccountId <= 0) {
            throw new IllegalArgumentException("Customer and account identifiers must be positive.");
        }
        this.customerId = customerId;
        this.authenticatedAccountId = authenticatedAccountId;
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.authenticatedAt = Objects.requireNonNull(authenticatedAt, "authenticatedAt");
    }

    public long customerId() { return customerId; }
    public long authenticatedAccountId() { return authenticatedAccountId; }
    public UUID sessionId() { return sessionId; }
    public Instant authenticatedAt() { return authenticatedAt; }

    @Override public String toString() {
        return "CustomerPrincipal[customerId=" + customerId
                + ", accountId=" + authenticatedAccountId + "]";
    }
}
