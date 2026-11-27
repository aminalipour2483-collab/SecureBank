package com.bank.fraudring.domain;

import java.util.Objects;
import java.util.Set;

/**
 * What the administrator asked the fraud-ring analysis to cover.
 *
 * <p>Mirrors {@link com.bank.graph.domain.GraphCriteria} in style: a validated
 * immutable value object with safe defaults and hard limits, because the
 * analysis walks the whole transfer graph in memory.
 *
 * @param scope              FULL graph, an explicit SUBSET of customers, or one SINGLE customer
 * @param customerIds        the SUBSET members (or the single id for SINGLE); empty for FULL
 * @param lookbackDays       only transfers from the last N days, or {@code null} for all time
 * @param includeSharedPhone also link customers that share a phone number
 * @param maxNodes           upper bound on customers in the analysis; the result is flagged
 *                           truncated when more exist
 */
public record FraudRingCriteria(FraudRingScope scope,
                                 Set<Long> customerIds,
                                 Integer lookbackDays,
                                 boolean includeSharedPhone,
                                 int maxNodes) {

    public static final int DEFAULT_MAX_NODES = 2_000;
    public static final int MAX_NODES_LIMIT = 5_000;
    public static final int MAX_SUBSET_SIZE = 200;
    public static final int MAX_LOOKBACK_DAYS = 3_650;

    /** Matches CustomerGraphService.MAX_DETAIL_CUSTOMERS: the subset drill-down ceiling. */
    public static final int MAX_SCOPE_CUSTOMERS = 200;

    public FraudRingCriteria {
        Objects.requireNonNull(scope, "scope");
        customerIds = customerIds == null ? Set.of() : Set.copyOf(customerIds);
        if (lookbackDays != null && (lookbackDays < 1 || lookbackDays > MAX_LOOKBACK_DAYS)) {
            throw new IllegalArgumentException("lookbackDays must be between 1 and " + MAX_LOOKBACK_DAYS);
        }
        if (maxNodes < 1 || maxNodes > MAX_NODES_LIMIT) {
            throw new IllegalArgumentException("maxNodes must be between 1 and " + MAX_NODES_LIMIT);
        }
        switch (scope) {
            case FULL -> {
                if (!customerIds.isEmpty()) {
                    throw new IllegalArgumentException("FULL scope takes no customer ids");
                }
            }
            case SUBSET -> {
                if (customerIds.isEmpty()) {
                    throw new IllegalArgumentException("SUBSET scope needs at least one customer id");
                }
                if (customerIds.size() > MAX_SUBSET_SIZE) {
                    throw new IllegalArgumentException("SUBSET scope takes at most " + MAX_SUBSET_SIZE + " customers");
                }
            }
            case SINGLE -> {
                if (customerIds.size() != 1) {
                    throw new IllegalArgumentException("SINGLE scope takes exactly one customer id");
                }
            }
        }
    }

    public static FraudRingCriteria full() {
        return new FraudRingCriteria(FraudRingScope.FULL, Set.of(), null, true, DEFAULT_MAX_NODES);
    }

    public static FraudRingCriteria subset(Set<Long> ids) {
        return new FraudRingCriteria(FraudRingScope.SUBSET, ids, null, true, DEFAULT_MAX_NODES);
    }

    public static FraudRingCriteria single(long customerId) {
        return new FraudRingCriteria(FraudRingScope.SINGLE, Set.of(customerId), null, true, DEFAULT_MAX_NODES);
    }

    public String describePeriod() {
        return lookbackDays == null ? "all time" : "last " + lookbackDays + " days";
    }

    public String describeScope() {
        return switch (scope) {
            case FULL -> "entire graph";
            case SUBSET -> customerIds.size() + " selected customers";
            case SINGLE -> "customer #" + customerIds.iterator().next();
        };
    }
}
