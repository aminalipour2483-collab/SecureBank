package com.bank.fraudring.application;

import com.bank.fraudring.domain.CustomerRiskFact;
import com.bank.fraudring.domain.FraudRingCriteria;
import com.bank.fraudring.domain.FraudRingFlow;
import com.bank.graph.domain.CustomerIdentity;

import java.util.List;
import java.util.Map;

/**
 * Port the fraud-ring use cases need from persistence. Implemented in the
 * infrastructure layer; the application layer never sees SQL — the same
 * dependency direction as {@code CustomerGraphRepository}.
 */
public interface FraudRingSourceRepository {

    /**
     * Reads the analysis inputs from ONE consistent read-only snapshot:
     * aggregated transfer flows, shared-phone groups, and per-customer tenure
     * facts. Implementations must bound row counts using
     * {@link FraudRingCriteria#maxNodes()} and report truncation instead of
     * silently cutting data.
     */
    FraudRingSnapshot loadSnapshot(FraudRingCriteria criteria);

    /** One snapshot's complete input bundle. */
    record FraudRingSnapshot(List<FraudRingFlow> flows,
                             boolean flowsTruncated,
                             Map<String, List<CustomerIdentity>> phoneGroups,
                             boolean phonesTruncated,
                             Map<Long, CustomerRiskFact> facts,
                             boolean factsTruncated) {

        public FraudRingSnapshot {
            flows = List.copyOf(flows);
            phoneGroups = Map.copyOf(phoneGroups);
            facts = Map.copyOf(facts);
        }

        public boolean truncated() {
            return flowsTruncated || phonesTruncated || factsTruncated;
        }
    }
}
