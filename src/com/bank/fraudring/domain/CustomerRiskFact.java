package com.bank.fraudring.domain;

/**
 * Extra per-customer fact the risk layer needs but the transfer flows do not
 * carry. Loaded on the same read-only snapshot as the flows, one row per
 * customer in scope.
 *
 * @param accountCount     accounts the customer holds
 * @param newestAccountAgeDays days since the customer's newest account was opened
 *                             (null = unknown / no account row); young accounts are a
 *                             money-mule staging signal
 * @param sharedPhoneCount distinct normalised phone numbers this customer shares
 *                         with at least one other customer (0 when shared-phone
 *                         linking is off)
 */
public record CustomerRiskFact(long customerId,
                               int accountCount,
                               Integer newestAccountAgeDays,
                               int sharedPhoneCount) {

    public CustomerRiskFact {
        if (customerId < 1) throw new IllegalArgumentException("customerId must be positive");
        if (accountCount < 0) throw new IllegalArgumentException("accountCount must not be negative");
        if (sharedPhoneCount < 0) throw new IllegalArgumentException("sharedPhoneCount must not be negative");
        if (newestAccountAgeDays != null && newestAccountAgeDays < 0) {
            throw new IllegalArgumentException("newestAccountAgeDays must not be negative");
        }
    }

    /** The value the NEW_ACCOUNT rule uses; MAX_VALUE when the age is unknown (never "young"). */
    public int accountAgeDaysOrUnknown() {
        return newestAccountAgeDays == null ? Integer.MAX_VALUE : newestAccountAgeDays;
    }

    public static CustomerRiskFact unknown(long customerId) {
        return new CustomerRiskFact(customerId, 0, null, 0);
    }

    public static CustomerRiskFact of(long customerId, int accountCount,
                                      Integer newestAccountAgeDays, int sharedPhoneCount) {
        return new CustomerRiskFact(customerId, accountCount, newestAccountAgeDays, sharedPhoneCount);
    }
}
