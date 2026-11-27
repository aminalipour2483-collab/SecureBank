package com.bank.fraudring.domain;

import java.util.List;
import java.util.Objects;

/**
 * The risk score of ONE customer inside one analysis run — a fully additive,
 * explainable 0–100 score. The score is the capped sum of the
 * {@link ScoredReason} points, so the reason list always adds up to the score
 * (XAI contract: «امتیاز = مجموع وزن دلایل، سقف ۱۰۰»).
 *
 * <p>Structural signals (cycles, centrality, community) and behavioural
 * signals (velocity, young accounts, shared phone, banned status) both appear
 * as reasons — one flat list, ranked by points desc, then code asc for
 * deterministic ordering.
 */
public record CustomerRiskScore(long customerId,
                                 String displayName,
                                 int score,
                                 RiskBand band,
                                 List<ScoredReason> reasons) {

    public CustomerRiskScore {
        if (customerId < 1) throw new IllegalArgumentException("customerId must be positive");
        Objects.requireNonNull(displayName, "displayName");
        if (score < 0 || score > 100) throw new IllegalArgumentException("score must be 0..100");
        Objects.requireNonNull(band, "band");
        reasons = List.copyOf(reasons);
        reasons = sorted(reasons);
        int sum = reasons.stream().mapToInt(ScoredReason::points).sum();
        // XAI contract: the score IS the reason sum, capped at 100.
        // sum == score (uncapped), or sum >= 100 with score == 100 (capped).
        if (score == 100 ? sum < 100 : sum != score) {
            throw new IllegalArgumentException("reason points (" + sum + ") must sum to the score ("
                    + score + "); the sum is only allowed to exceed a score of exactly 100");
        }
        if (band != RiskBand.of(score)) {
            throw new IllegalArgumentException("band must match the score");
        }
    }

    /** Deterministic reason order: points desc, then code asc. */
    private static List<ScoredReason> sorted(List<ScoredReason> reasons) {
        return reasons.stream()
                .sorted(java.util.Comparator.<ScoredReason>comparingInt(ScoredReason::points).reversed()
                        .thenComparing(ScoredReason::code))
                .toList();
    }

    /** Machine-readable code list for audit lines: "MONEY_CYCLE+SHARED_PHONE". */
    public String reasonCodes() {
        return reasons.stream().map(ScoredReason::code)
                .reduce((a, b) -> a + "+" + b).orElse("");
    }

    /** Total transfer volume this customer sent or received inside the analysis. */
    public CustomerRiskScore withDisplayName(String name) {
        return new CustomerRiskScore(customerId, name, score, band, reasons);
    }
}
