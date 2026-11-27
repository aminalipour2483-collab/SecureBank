package com.bank.fraudring.application;

import com.bank.fraudring.domain.FraudRingAnalysis;

import java.util.Optional;

/**
 * Port the fraud-ring use cases need for PERSISTED ANALYSIS OUTPUT. The
 * Phase 2 service referenced the JDBC implementation class directly; Phase 3
 * moves it behind this port so the application layer is infrastructure-free
 * (same dependency direction as {@link FraudRingSourceRepository}).
 */
public interface FraudRingResultRepository {

    /**
     * Stores one completed analysis. Returns the stored row id.
     *
     * @throws DuplicateAnalysisException when a concurrent run already stored
     *         this idempotency key — the caller replays instead
     */
    long insert(FraudRingAnalysis analysis);

    /** Loads a stored analysis by its idempotency key (replay path). */
    Optional<FraudRingAnalysis> findByKey(String idempotencyKey);

    /**
     * Mirrors the JDBC adapter's duplicate-key semantics so the application
     * layer can catch it without importing infrastructure types.
     */
    class DuplicateAnalysisException extends RuntimeException {
        public DuplicateAnalysisException(String key, Throwable cause) {
            super("Analysis with key " + key + " already stored by a concurrent run", cause);
        }
    }
}
