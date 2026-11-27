package com.bank.graph.domain;

/**
 * What the administrator asked the graph to contain.
 *
 * @param lookbackDays      only transfers from the last N days, or {@code null} for all time
 * @param includeSharedPhone also link customers that share a phone number
 * @param maxEdges          upper bound on relationships returned <em>per relationship type</em>.
 *                          Transfer links keep the largest by total amount; shared-phone links have no
 *                          strength and are kept in phone/customer-id order. Either way the graph is
 *                          flagged truncated.
 */
public record GraphCriteria(Integer lookbackDays, boolean includeSharedPhone, int maxEdges) {

    public static final int DEFAULT_MAX_EDGES = 1_000;
    public static final int MAX_EDGES_LIMIT = 5_000;
    public static final int MAX_LOOKBACK_DAYS = 3_650;

    public GraphCriteria {
        if (lookbackDays != null && (lookbackDays < 1 || lookbackDays > MAX_LOOKBACK_DAYS)) {
            throw new IllegalArgumentException("lookbackDays must be between 1 and " + MAX_LOOKBACK_DAYS);
        }
        if (maxEdges < 1 || maxEdges > MAX_EDGES_LIMIT) {
            throw new IllegalArgumentException("maxEdges must be between 1 and " + MAX_EDGES_LIMIT);
        }
    }

    public static GraphCriteria defaults() {
        return new GraphCriteria(null, true, DEFAULT_MAX_EDGES);
    }

    public String describePeriod() {
        return lookbackDays == null ? "all time" : "last " + lookbackDays + " days";
    }
}
