package com.bank.fraudring.domain;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * One COMPLETED analysis run as it is stored in and replayed from
 * {@code fraud_ring_analyses}. The three JSON columns carry the derived
 * snapshot (structural summary, ranked customer risks, ranked group risks).
 *
 * <p>Phase 2 fills the structural {@code graphStatsJson}; the risk columns are
 * written by the Phase 3 scoring layer on the same call path — the table
 * contract (NOT NULL JSON) is satisfied by an explanatory placeholder payload
 * that the Phase 3 engine replaces, so the Phase 2 code alone never inserts
 * half-empty rows.
 *
 * @param analysisId        generated PK
 * @param idempotencyKey    fingerprint of the canonical request (SHA-256 hex)
 * @param scope             FULL / SUBSET / SINGLE
 * @param scopeCustomerId   set for SINGLE only
 * @param lookbackDays      null = all time
 * @param graphStatsJson    nodes/edges/communities/cycles summary JSON
 * @param topCustomersJson  ranked customer risks with XAI reasons JSON
 * @param topGroupsJson     ranked group risks with XAI reasons JSON
 * @param analyzedBy        admin username (audit)
 * @param analyzedAt        wall-clock time of the run (audit)
 */
public record FraudRingAnalysis(long analysisId,
                                String idempotencyKey,
                                FraudRingScope scope,
                                Long scopeCustomerId,
                                Integer lookbackDays,
                                String graphStatsJson,
                                String topCustomersJson,
                                String topGroupsJson,
                                String analyzedBy,
                                LocalDateTime analyzedAt) {

    public FraudRingAnalysis {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey is required");
        }
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(graphStatsJson, "graphStatsJson");
        Objects.requireNonNull(topCustomersJson, "topCustomersJson");
        Objects.requireNonNull(topGroupsJson, "topGroupsJson");
        Objects.requireNonNull(analyzedBy, "analyzedBy");
        Objects.requireNonNull(analyzedAt, "analyzedAt");
        if (scope == FraudRingScope.SINGLE && scopeCustomerId == null) {
            throw new IllegalArgumentException("SINGLE scope needs scopeCustomerId");
        }
        if (scope != FraudRingScope.SINGLE && scopeCustomerId != null) {
            throw new IllegalArgumentException("Only SINGLE scope sets scopeCustomerId");
        }
        if (lookbackDays != null && (lookbackDays < 1 || lookbackDays > FraudRingCriteria.MAX_LOOKBACK_DAYS)) {
            throw new IllegalArgumentException("lookbackDays out of range");
        }
    }
}
