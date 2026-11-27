package com.bank.fraudring.application;

import com.bank.exception.AccessDeniedException;
import com.bank.fraudring.domain.FraudRingAnalysis;
import com.bank.fraudring.domain.FraudRingCriteria;
import com.bank.security.AdminPrincipal;

import java.util.Optional;

/**
 * Administrator-only use cases of the fraud-ring module (Phase 2 surface;
 * Phase 3 adds the risk-scoring payload on the same analyze call).
 * Every method requires an {@link AdminPrincipal}.
 */
public interface FraudRingService {

    /**
     * Runs the structural fraud-ring analysis (communities, money cycles,
     * centrality) on a consistent read-only snapshot and stores the result
     * under an idempotency key derived from the canonical request.
     *
     * <p><b>Idempotency.</b> Re-running the same request replays the stored
     * analysis instead of running the graph algorithms again — the returned
     * {@link FraudRingAnalysis#analysisId()} is the same row, no duplicate is
     * inserted. A concurrent first-run waits on the unique key, then replays
     * the winner.
     *
     * @throws AccessDeniedException if {@code admin} is null
     * @throws com.bank.exception.BankException on infrastructure failure
     */
    FraudRingAnalysis analyze(AdminPrincipal admin, FraudRingCriteria criteria);

    /**
     * Loads a previously stored analysis by its idempotency key (admin replay).
     *
     * @throws AccessDeniedException if {@code admin} is null
     */
    Optional<FraudRingAnalysis> findAnalysis(AdminPrincipal admin, String idempotencyKey);
}
