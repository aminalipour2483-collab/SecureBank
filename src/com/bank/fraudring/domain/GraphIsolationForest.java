package com.bank.fraudring.domain;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

/**
 * Deterministic, unsupervised Isolation Forest for customer-level graph features.
 *
 * <p>Implements the isolation-tree scoring method described by Liu, Ting and
 * Zhou (IEEE ICDM 2008). It does not require fraud labels. The forest is built
 * from the current graph snapshot and is deliberately disabled for fewer than
 * MIN_BASELINE_SIZE observations: a tiny peer group is not a credible baseline.
 *
 * <p>The returned score is an anomaly ranking signal in [0,1], NOT a fraud
 * probability. It must be interpreted with rule/graph evidence and reviewed by
 * a human. A fixed seed makes identical snapshots deterministic.
 */
public final class GraphIsolationForest {

    public static final int MIN_BASELINE_SIZE = 32;
    public static final int TREE_COUNT = 100;
    public static final int MAX_SAMPLE_SIZE = 256;
    public static final double ANOMALY_THRESHOLD = 0.65d;
    private static final long SEED = 0x5345435552454241L;

    private GraphIsolationForest() { }

    public enum Status { ACTIVE, INSUFFICIENT_BASELINE, INCOMPLETE_SNAPSHOT }

    public record Result(Status status, int baselineSize, Map<Long, Double> scores) {
        public Result {
            scores = Collections.unmodifiableMap(new LinkedHashMap<>(scores));
        }
        public boolean active() { return status == Status.ACTIVE; }
        public double scoreFor(long customerId) {
            return scores.getOrDefault(customerId, Double.NaN);
        }
    }

    /**
     * @param observations customer id -> finite, fixed-width feature vector
     */
    public static Result incompleteSnapshot(int observedNodes) {
        return new Result(Status.INCOMPLETE_SNAPSHOT, Math.max(0, observedNodes), Map.of());
    }

    public static Result score(Map<Long, double[]> observations) {
        if (observations == null) throw new IllegalArgumentException("observations must not be null");
        TreeMap<Long, double[]> ordered = new TreeMap<>();
        int dimensions = -1;
        for (Map.Entry<Long, double[]> entry : observations.entrySet()) {
            Long id = entry.getKey();
            double[] vector = entry.getValue();
            if (id == null || id < 1 || vector == null || vector.length == 0) {
                throw new IllegalArgumentException("each observation needs a positive id and non-empty vector");
            }
            if (dimensions < 0) dimensions = vector.length;
            if (vector.length != dimensions) throw new IllegalArgumentException("feature dimensions differ");
            double[] copy = vector.clone();
            for (double value : copy) {
                if (!Double.isFinite(value)) throw new IllegalArgumentException("features must be finite");
            }
            ordered.put(id, copy);
        }
        int n = ordered.size();
        if (n < MIN_BASELINE_SIZE) {
            return new Result(Status.INSUFFICIENT_BASELINE, n, Map.of());
        }

        List<Long> ids = new ArrayList<>(ordered.keySet());
        double[][] data = new double[n][dimensions];
        for (int i = 0; i < n; i++) data[i] = ordered.get(ids.get(i));

        int sampleSize = Math.min(MAX_SAMPLE_SIZE, n);
        int heightLimit = (int) Math.ceil(Math.log(sampleSize) / Math.log(2.0d));
        Random random = new Random(SEED ^ n ^ (long) dimensions << 32);
        Node[] forest = new Node[TREE_COUNT];
        for (int t = 0; t < TREE_COUNT; t++) {
            int[] sample = sampleWithoutReplacement(n, sampleSize, random);
            forest[t] = build(data, sample, 0, heightLimit, random);
        }

        double normalizer = averagePathLength(sampleSize);
        Map<Long, Double> scores = new LinkedHashMap<>();
        for (int row = 0; row < n; row++) {
            double pathTotal = 0d;
            for (Node tree : forest) pathTotal += pathLength(data[row], tree, 0);
            double averagePath = pathTotal / TREE_COUNT;
            double anomaly = normalizer <= 0d ? 0.5d
                    : Math.pow(2d, -averagePath / normalizer);
            scores.put(ids.get(row), clamp(anomaly, 0d, 1d));
        }
        return new Result(Status.ACTIVE, n, scores);
    }

    private static int[] sampleWithoutReplacement(int n, int sampleSize, Random random) {
        int[] values = new int[n];
        for (int i = 0; i < n; i++) values[i] = i;
        for (int i = 0; i < sampleSize; i++) {
            int j = i + random.nextInt(n - i);
            int tmp = values[i]; values[i] = values[j]; values[j] = tmp;
        }
        return Arrays.copyOf(values, sampleSize);
    }

    private static Node build(double[][] data, int[] rows, int depth,
                              int heightLimit, Random random) {
        if (rows.length <= 1 || depth >= heightLimit) return Node.leaf(rows.length);

        double[] min = new double[data[0].length];
        double[] max = new double[data[0].length];
        Arrays.fill(min, Double.POSITIVE_INFINITY);
        Arrays.fill(max, Double.NEGATIVE_INFINITY);
        for (int row : rows) {
            for (int f = 0; f < min.length; f++) {
                min[f] = Math.min(min[f], data[row][f]);
                max[f] = Math.max(max[f], data[row][f]);
            }
        }
        int[] usable = new int[min.length];
        int usableCount = 0;
        for (int f = 0; f < min.length; f++) if (max[f] > min[f]) usable[usableCount++] = f;
        if (usableCount == 0) return Node.leaf(rows.length);

        int feature = usable[random.nextInt(usableCount)];
        double split = min[feature] + random.nextDouble() * (max[feature] - min[feature]);
        int leftCount = 0;
        for (int row : rows) if (data[row][feature] < split) leftCount++;
        if (leftCount == 0 || leftCount == rows.length) return Node.leaf(rows.length);
        int[] leftRows = new int[leftCount];
        int[] rightRows = new int[rows.length - leftCount];
        int li = 0, ri = 0;
        for (int row : rows) {
            if (data[row][feature] < split) leftRows[li++] = row;
            else rightRows[ri++] = row;
        }
        return Node.branch(feature, split,
                build(data, leftRows, depth + 1, heightLimit, random),
                build(data, rightRows, depth + 1, heightLimit, random));
    }

    private static double pathLength(double[] row, Node node, int depth) {
        if (node.leaf()) return depth + averagePathLength(node.size);
        return row[node.feature] < node.split
                ? pathLength(row, node.left, depth + 1)
                : pathLength(row, node.right, depth + 1);
    }

    /** c(n) = 2H(n-1) - 2(n-1)/n, the expected unsuccessful BST search path. */
    private static double averagePathLength(int n) {
        if (n <= 1) return 0d;
        if (n == 2) return 1d;
        double harmonic = 0d;
        for (int i = 1; i < n; i++) harmonic += 1d / i;
        return 2d * harmonic - (2d * (n - 1)) / n;
    }

    private static double clamp(double value, double low, double high) {
        return Math.max(low, Math.min(high, value));
    }

    private static final class Node {
        final int feature;
        final double split;
        final Node left;
        final Node right;
        final int size;
        private Node(int feature, double split, Node left, Node right, int size) {
            this.feature = feature; this.split = split; this.left = left; this.right = right; this.size = size;
        }
        static Node leaf(int size) { return new Node(-1, 0d, null, null, size); }
        static Node branch(int feature, double split, Node left, Node right) {
            return new Node(feature, split, left, right, left.size + right.size);
        }
        boolean leaf() { return feature < 0; }
    }
}
