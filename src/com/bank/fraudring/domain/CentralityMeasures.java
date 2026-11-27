package com.bank.fraudring.domain;

import java.util.List;
import java.util.Objects;

/**
 * Centrality metrics for one customer in the analysis graph — the structural
 * "how important is this node" half of the risk scoring.
 *
 * <p>All three classic families are computed deterministically in
 * {@link FraudRingAnalyzer}:
 * <ul>
 *   <li><b>degree</b> — distinct counterparties (undirected view of the transfer
 *       network). High degree = the node talks to many people; a money mule
 *       typically relays between many counterparties.</li>
 *   <li><b>betweenness</b> — fraction of shortest paths (Brandes, unweighted,
 *       undirected) that pass through the node, normalised to 0..1 by the
 *       standard (n-1)(n-2)/2 divisor. High betweenness = the node sits
 *       <em>between</em> clusters — the courier profile.</li>
 *   <li><b>pageRank</b> — power iteration on the directed transfer network with
 *       damping 0.85 and a fixed iteration cap, seeded by out-degree. High
 *       PageRank = the node is a destination lots of money-hubs point at.</li>
 * </ul>
 * Values are rounded (degree is exact by nature) so repeated runs on the same
 * snapshot produce byte-identical results (determinism requirement).
 *
 * @param customerId   the node
 * @param degree       distinct undirected counterparties (0 for an isolated node)
 * @param betweenness  0..1, rounded to 4 decimals
 * @param pageRank     power-iteration score, rounded to 6 decimals; not normalised to 1
 *                     across the graph, only comparable WITHIN one analysis
 */
public record CentralityMeasures(long customerId,
                                 int degree,
                                 double betweenness,
                                 double pageRank) {

    public CentralityMeasures {
        if (customerId < 1) throw new IllegalArgumentException("customerId must be positive");
        if (degree < 0) throw new IllegalArgumentException("degree must not be negative");
        if (betweenness < 0 || betweenness > 1.0001) {
            throw new IllegalArgumentException("betweenness is a 0..1 fraction");
        }
        if (pageRank < 0) throw new IllegalArgumentException("pageRank must not be negative");
        betweenness = round4(betweenness);
        pageRank = round6(pageRank);
    }

    private static double round4(double v) { return Math.round(v * 10_000d) / 10_000d; }
    private static double round6(double v) { return Math.round(v * 1_000_000d) / 1_000_000d; }

    /** Convenience for the risk layer: is this node one of the busiest in the analysis? */
    public boolean isHub(double degreePercentileThreshold, int maxDegreeInGraph) {
        return maxDegreeInGraph > 0 && degree >= Math.ceil(maxDegreeInGraph * degreePercentileThreshold);
    }

    public static CentralityMeasures isolated(long customerId) {
        return new CentralityMeasures(customerId, 0, 0d, 0d);
    }

    public static CentralityMeasures of(long customerId, int degree, double betweenness, double pageRank) {
        return new CentralityMeasures(customerId, degree, betweenness, pageRank);
    }

    /** Placeholder removal marker — never called; keeps `Objects` import meaningful. */
    static void unused(Objects o) { Objects.requireNonNull(o); }
}
