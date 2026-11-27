package com.bank.fraudring;

import com.bank.fraudring.domain.GraphIsolationForest;
import com.bank.testkit.TestKit;

import java.util.LinkedHashMap;
import java.util.Map;

/** Tests the unsupervised forest independently of JDBC, Swing and fraud labels. */
public final class GraphIsolationForestTest {

    public static void main(String[] args) {
        TestKit.section("[1] baseline requirements");
        insufficientBaselineIsExplicit();
        TestKit.section("[2] isolation scoring");
        isolatesSyntheticStructuralOutlier();
        scoresAreDeterministicAndBounded();
        TestKit.section("[3] input contracts");
        rejectsInconsistentFeatures();
        TestKit.finish("GraphIsolationForestTest");
    }

    private static void insufficientBaselineIsExplicit() {
        Map<Long, double[]> rows = new LinkedHashMap<>();
        for (long id = 1; id <= GraphIsolationForest.MIN_BASELINE_SIZE - 1; id++) {
            rows.put(id, new double[]{id, id * 0.5d});
        }
        GraphIsolationForest.Result result = GraphIsolationForest.score(rows);
        TestKit.checkEquals("small peer group disables model",
                GraphIsolationForest.Status.INSUFFICIENT_BASELINE, result.status());
        TestKit.check("no misleading scores emitted", result.scores().isEmpty());
    }

    private static void isolatesSyntheticStructuralOutlier() {
        Map<Long, double[]> rows = normalRows(80);
        rows.put(999L, new double[]{100d, 150d, 80d, 60d});
        GraphIsolationForest.Result result = GraphIsolationForest.score(rows);
        double outlier = result.scoreFor(999L);
        double meanNormal = rows.keySet().stream().filter(id -> id != 999L)
                .mapToDouble(result::scoreFor).average().orElse(0d);
        TestKit.checkEquals("model active at minimum sample size",
                GraphIsolationForest.Status.ACTIVE, result.status());
        TestKit.check("synthetic isolated point scores above normal population",
                outlier > meanNormal,
                "outlier=" + outlier + " normalMean=" + meanNormal);
        TestKit.check("anomaly score is not a probability but remains bounded",
                outlier >= 0d && outlier <= 1d);
    }

    private static void scoresAreDeterministicAndBounded() {
        Map<Long, double[]> rows = normalRows(64);
        GraphIsolationForest.Result first = GraphIsolationForest.score(rows);
        GraphIsolationForest.Result second = GraphIsolationForest.score(rows);
        TestKit.checkEquals("same snapshot produces identical score map", first.scores(), second.scores());
        TestKit.check("all scores are finite and in [0,1]",
                first.scores().values().stream().allMatch(v -> Double.isFinite(v) && v >= 0d && v <= 1d));
    }

    private static void rejectsInconsistentFeatures() {
        Map<Long, double[]> rows = new LinkedHashMap<>();
        rows.put(1L, new double[]{1d, 2d});
        rows.put(2L, new double[]{1d});
        TestKit.checkThrows("feature dimension mismatch rejected", IllegalArgumentException.class,
                () -> GraphIsolationForest.score(rows));
    }

    private static Map<Long, double[]> normalRows(int count) {
        Map<Long, double[]> rows = new LinkedHashMap<>();
        for (long id = 1; id <= count; id++) {
            double x = (id % 7) * 0.08d;
            double y = (id % 5) * 0.06d;
            rows.put(id, new double[]{x, y, x + y, Math.abs(x - y)});
        }
        return rows;
    }
}
