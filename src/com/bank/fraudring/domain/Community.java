package com.bank.fraudring.domain;

import java.util.List;
import java.util.Objects;

/**
 * A community of customers detected by the analyzer's label-propagation pass
 * (Louvain-lite): a set of customers more densely connected among themselves
 * than to the rest of the graph. Communities are the candidate "fraud rings".
 *
 * <p>Determinism: the analyzer iterates nodes and candidate communities in
 * customer-id order and retains the current community on equal modularity gain.
 * The same snapshot therefore yields the same partition; ids are stable only
 * within one analysis run (they are list positions of the community sort).
 *
 * @param communityId     stable-within-run id (0-based, communities sorted by size desc, then lowest member id)
 * @param members         customer identities, ascending by id
 * @param density         unique undirected customer-pair edges / possible undirected pairs;
 *                        rounded to 4 decimals; 0 for singletons
 * @param internalFlows   unique connected customer pairs with BOTH endpoints inside the community
 * @param internalVolume  sum of directed transfer amounts whose endpoints are both inside
 * @param sharedPhones    normalised phone numbers shared by 2+ members (may be empty)
 */
public record Community(long communityId,
                        List<com.bank.graph.domain.CustomerIdentity> members,
                        double density,
                        int internalFlows,
                        java.math.BigDecimal internalVolume,
                        List<String> sharedPhones) {

    public Community {
        members = List.copyOf(members);
        sharedPhones = List.copyOf(sharedPhones == null ? List.of() : sharedPhones);
        Objects.requireNonNull(internalVolume, "internalVolume");
        if (members.isEmpty()) throw new IllegalArgumentException("A community is never empty");
        if (density < 0 || density > 1.0001) throw new IllegalArgumentException("density is a 0..1 fraction");
        if (internalFlows < 0) throw new IllegalArgumentException("internalFlows must not be negative");
        density = Math.round(density * 10_000d) / 10_000d;
        internalVolume = internalVolume.setScale(2, java.math.RoundingMode.HALF_UP);
    }

    public int size() { return members.size(); }

    public boolean involves(long customerId) {
        return members.stream().anyMatch(m -> m.id() == customerId);
    }

    public List<Long> memberIds() {
        return members.stream().map(com.bank.graph.domain.CustomerIdentity::id).toList();
    }

    /** Rings worth flagging have at least this many members (2). */
    public static final int MIN_RING_SIZE = 2;
}
