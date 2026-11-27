package com.bank.fraudring.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * The risk score of ONE community (a candidate fraud ring) inside one
 * analysis run — same additive 0–100 contract as {@link CustomerRiskScore}.
 *
 * <p>Group reasons describe the RING as a whole: money cycles inside the
 * group, internal volume, shared phones between members, banned members,
 * density. The members' individual scores stay on the customer rows; the
 * group score is computed from group-level facts only, so it is not just
 * "the max of the members" — two innocents in a dense money-cycling group
 * still light the group up.
 */
public record GroupRiskScore(int communityId,
                              List<Long> memberIds,
                              int score,
                              RiskBand band,
                              List<ScoredReason> reasons) {

    public GroupRiskScore {
        if (communityId < 0) throw new IllegalArgumentException("communityId must not be negative");
        Objects.requireNonNull(memberIds, "memberIds");
        memberIds = List.copyOf(memberIds);
        if (memberIds.isEmpty()) throw new IllegalArgumentException("a scored group is never empty");
        if (score < 0 || score > 100) throw new IllegalArgumentException("score must be 0..100");
        Objects.requireNonNull(band, "band");
        reasons = List.copyOf(reasons);
        reasons = reasons.stream()
                .sorted(java.util.Comparator.<ScoredReason>comparingInt(ScoredReason::points).reversed()
                        .thenComparing(ScoredReason::code))
                .toList();
        int sum = reasons.stream().mapToInt(ScoredReason::points).sum();
        // XAI contract: the score IS the reason sum, capped at 100.
        if (score == 100 ? sum < 100 : sum != score) {
            throw new IllegalArgumentException("reason points (" + sum + ") must sum to the score ("
                    + score + "); the sum is only allowed to exceed a score of exactly 100");
        }
        if (band != RiskBand.of(score)) {
            throw new IllegalArgumentException("band must match the score");
        }
    }

    /** Machine-readable code list for audit lines. */
    public String reasonCodes() {
        return reasons.stream().map(ScoredReason::code)
                .reduce((a, b) -> a + "+" + b).orElse("");
    }
}
