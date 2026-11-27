package com.bank.fraudring.domain;

import java.util.List;
import java.util.Objects;

/**
 * The complete risk output of one analysis run: every customer scored, every
 * community scored, deterministic ranking (score desc, id asc), and the
 * band counts the admin UI summarises in one glance.
 */
public record RiskAssessment(List<CustomerRiskScore> customers,
                              List<GroupRiskScore> groups,
                              GraphIsolationForest.Status anomalyModelStatus,
                              int anomalyBaselineSize) {

    /** Backwards-compatible constructor for callers/tests that provide no model metadata. */
    public RiskAssessment(List<CustomerRiskScore> customers, List<GroupRiskScore> groups) {
        this(customers, groups, GraphIsolationForest.Status.INSUFFICIENT_BASELINE, 0);
    }

    public RiskAssessment {
        customers = List.copyOf(customers);
        groups = List.copyOf(groups);
        Objects.requireNonNull(anomalyModelStatus, "anomalyModelStatus");
        if (anomalyBaselineSize < 0) throw new IllegalArgumentException("anomalyBaselineSize must not be negative");
        // deterministic ranking: score desc, then id asc (customers) / communityId asc (groups)
        customers = customers.stream()
                .sorted(java.util.Comparator.<CustomerRiskScore>comparingInt(CustomerRiskScore::score).reversed()
                        .thenComparingLong(CustomerRiskScore::customerId))
                .toList();
        groups = groups.stream()
                .sorted(java.util.Comparator.<GroupRiskScore>comparingInt(GroupRiskScore::score).reversed()
                        .thenComparingInt(GroupRiskScore::communityId))
                .toList();
    }

    public int customerCount() { return customers.size(); }
    public int groupCount() { return groups.size(); }

    public CustomerRiskScore customer(long customerId) {
        return customers.stream().filter(c -> c.customerId() == customerId).findFirst().orElse(null);
    }

    public int countBand(RiskBand band) {
        return (int) customers.stream().filter(c -> c.band() == band).count();
    }

    public int groupCountBand(RiskBand band) {
        return (int) groups.stream().filter(g -> g.band() == band).count();
    }

    /** Highest customer score, or 0 on an empty analysis. */
    public int maxCustomerScore() {
        return customers.stream().mapToInt(CustomerRiskScore::score).max().orElse(0);
    }

    /** Top-N customers without re-ranking (the list is already ranked). */
    public List<CustomerRiskScore> topCustomers(int n) {
        Objects.checkIndex(n, customers.size() + 1);
        return customers.subList(0, n);
    }

    /** Top-N groups without re-ranking. */
    public List<GroupRiskScore> topGroups(int n) {
        Objects.checkIndex(n, groups.size() + 1);
        return groups.subList(0, n);
    }
}
