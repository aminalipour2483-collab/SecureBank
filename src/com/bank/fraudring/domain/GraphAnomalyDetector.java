package com.bank.fraudring.domain;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Builds customer-level structural/behavioural features and scores them with Isolation Forest. */
public final class GraphAnomalyDetector {

    private GraphAnomalyDetector() { }

    public static GraphIsolationForest.Result analyze(FraudRingGraph graph) {
        return analyze(graph, true);
    }

    /** A truncated snapshot is not a trustworthy peer baseline. */
    public static GraphIsolationForest.Result analyze(FraudRingGraph graph, boolean completeSnapshot) {
        if (graph == null) throw new IllegalArgumentException("graph must not be null");
        if (!completeSnapshot) return GraphIsolationForest.incompleteSnapshot(graph.nodeCount());
        return GraphIsolationForest.score(featureVectors(graph));
    }

    /** Fresh vectors in stable customer-id order; used by the offline research benchmark. */
    public static Map<Long, double[]> featureVectors(FraudRingGraph graph) {
        if (graph == null) throw new IllegalArgumentException("graph must not be null");
        Map<Long, MutableFeatures> features = new TreeMap<>();
        for (long id : graph.sortedNodeIds()) features.put(id, new MutableFeatures());

        for (FraudRingFlow flow : graph.flows()) {
            if (flow.transferCount() <= 0) continue;
            long from = flow.outgoing() ? flow.customer().id() : flow.counterpart().id();
            long to = flow.outgoing() ? flow.counterpart().id() : flow.customer().id();
            MutableFeatures source = features.get(from);
            MutableFeatures target = features.get(to);
            if (source == null || target == null) continue;
            source.outCount = safeAdd(source.outCount, flow.transferCount());
            target.inCount = safeAdd(target.inCount, flow.transferCount());
            source.outAmount = source.outAmount.add(flow.totalAmount());
            target.inAmount = target.inAmount.add(flow.totalAmount());
            source.outNeighbors.add(to);
            target.inNeighbors.add(from);
            source.maxPairCount = Math.max(source.maxPairCount, flow.transferCount());
            target.maxPairCount = Math.max(target.maxPairCount, flow.transferCount());
        }

        Map<Long, Integer> cycleCounts = new HashMap<>();
        for (MoneyCycle cycle : graph.cycles()) {
            for (long id : cycle.memberIds()) cycleCounts.merge(id, 1, Integer::sum);
        }
        Map<Long, Double> communityDensity = new HashMap<>();
        for (Community community : graph.communities()) {
            for (long id : community.memberIds()) communityDensity.put(id, community.density());
        }

        Map<Long, double[]> vectors = new LinkedHashMap<>();
        int nodeCount = Math.max(1, graph.nodeCount());
        for (long id : graph.sortedNodeIds()) {
            MutableFeatures f = features.get(id);
            int reciprocal = 0;
            for (long neighbor : f.outNeighbors) {
                MutableFeatures other = features.get(neighbor);
                if (other != null && other.outNeighbors.contains(id)) reciprocal++;
            }
            CentralityMeasures centrality = graph.centralityOf(id);
            CustomerRiskFact fact = graph.factOf(id);
            Integer age = fact.newestAccountAgeDays();
            double[] vector = new double[] {
                log1p(f.outCount),
                log1p(f.inCount),
                log1p(f.outAmount),
                log1p(f.inAmount),
                log1p(f.outNeighbors.size()),
                log1p(f.inNeighbors.size()),
                log1p(reciprocal),
                log1p(fact.sharedPhoneCount()),
                log1p(fact.accountCount()),
                age == null ? 0d : log1p(age),
                age == null ? 1d : 0d,
                centrality.betweenness(),
                log1p(centrality.pageRank() * nodeCount),
                log1p(cycleCounts.getOrDefault(id, 0)),
                communityDensity.getOrDefault(id, 0d),
                log1p(f.maxPairCount)
            };
            vectors.put(id, vector);
        }
        return vectors;
    }

    private static double log1p(long value) { return Math.log1p(Math.max(0L, value)); }
    private static double log1p(int value) { return Math.log1p(Math.max(0, value)); }
    private static double log1p(double value) { return Math.log1p(Double.isFinite(value) ? Math.max(0d, value) : 0d); }
    private static double log1p(BigDecimal value) {
        double amount = value == null ? 0d : value.doubleValue();
        if (!Double.isFinite(amount) || amount < 0d) amount = amount > 0d ? Double.MAX_VALUE : 0d;
        return Math.log1p(amount);
    }
    private static long safeAdd(long a, long b) {
        return Long.MAX_VALUE - a < b ? Long.MAX_VALUE : a + b;
    }

    private static final class MutableFeatures {
        long outCount;
        long inCount;
        long maxPairCount;
        BigDecimal outAmount = BigDecimal.ZERO;
        BigDecimal inAmount = BigDecimal.ZERO;
        final Set<Long> outNeighbors = new HashSet<>();
        final Set<Long> inNeighbors = new HashSet<>();
    }
}
