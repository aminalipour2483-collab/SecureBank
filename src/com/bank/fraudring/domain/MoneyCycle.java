package com.bank.fraudring.domain;

import com.bank.graph.domain.CustomerIdentity;

import java.util.List;
import java.util.Objects;

/**
 * A directed money cycle found in the aggregated transfer graph — money moving
 * in a ring, the classic AML layering pattern (A→B→C→A).
 *
 * <p>Cycles are reported on the <em>customer</em> graph (direction kept), not
 * the account graph, because fraud-ring analysis is about people, not products.
 * Self-transfers never enter the analysis (excluded in SQL and re-checked by
 * {@link FraudRingFlow}).
 *
 * @param members        the customers on the ring, in walk order; members[0] == members[last]
 *                      is NOT duplicated — the ring closes implicitly back to members[0]
 * @param hopCount       number of transfer hops on the ring (== members.size())
 * @param totalAmount    sum of the edge amounts along the ring
 * @param minEdgeAmount weakest edge amount on the ring — caps what the ring could launder
 *                      in one lap (min-flow / cut of the ring)
 * @param lastActivity   most recent transfer among the ring's edges
 */
public record MoneyCycle(List<CustomerIdentity> members,
                         int hopCount,
                         java.math.BigDecimal totalAmount,
                         java.math.BigDecimal minEdgeAmount,
                         java.time.LocalDateTime lastActivity) {

    public MoneyCycle {
        members = List.copyOf(members);
        Objects.requireNonNull(totalAmount, "totalAmount");
        Objects.requireNonNull(minEdgeAmount, "minEdgeAmount");
        if (members.size() < MIN_HOPS) {
            throw new IllegalArgumentException("A money cycle needs at least " + MIN_HOPS + " members");
        }
        if (hopCount != members.size()) {
            throw new IllegalArgumentException("hopCount must equal the member count");
        }
        long distinct = members.stream().map(CustomerIdentity::id).distinct().count();
        if (distinct != members.size()) {
            throw new IllegalArgumentException("A money cycle must not visit a customer twice");
        }
        if (minEdgeAmount.signum() <= 0 || totalAmount.signum() <= 0) {
            throw new IllegalArgumentException("Cycle amounts must be positive");
        }
        totalAmount = totalAmount.setScale(2, java.math.RoundingMode.HALF_UP);
        minEdgeAmount = minEdgeAmount.setScale(2, java.math.RoundingMode.HALF_UP);
    }

    /** Shortest ring worth reporting: A→B→A (a 2-customer back-and-forth). */
    public static final int MIN_HOPS = 2;

    /** Longest ring the analyzer searches for (bounded DFS depth). */
    public static final int MAX_HOPS = 8;

    public boolean involves(long customerId) {
        return members.stream().anyMatch(m -> m.id() == customerId);
    }

    public List<Long> memberIds() {
        return members.stream().map(CustomerIdentity::id).toList();
    }
}
