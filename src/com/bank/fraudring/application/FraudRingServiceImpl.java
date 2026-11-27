package com.bank.fraudring.application;

import com.bank.exception.BankException;
import com.bank.fraudring.domain.FraudRingAnalysis;
import com.bank.fraudring.domain.FraudRingAnalyzer;
import com.bank.fraudring.domain.FraudRingCriteria;
import com.bank.fraudring.domain.FraudRingGraph;
import com.bank.fraudring.domain.RiskAssessment;
import com.bank.fraudring.domain.RiskScoringEngine;
import com.bank.security.AdminGuard;
import com.bank.security.AdminPrincipal;
import com.bank.util.IdempotencyUtil;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.logging.Logger;

/**
 * Phase 3 application service: authorise → snapshot → analyse (in memory) →
 * <b>score (in memory)</b> → store-or-replay. The pipeline never holds a
 * connection during the graph algorithms or the scoring.
 *
 * <p><b>Layering.</b> Mirrors {@code CustomerGraphServiceImpl}: the application
 * layer owns authorisation ({@code AdminGuard.require}) and the audit record;
 * SQL stays behind the infrastructure ports ({@link FraudRingSourceRepository},
 * {@link FraudRingResultRepository}); the domain algorithms
 * ({@link FraudRingAnalyzer}, {@link RiskScoringEngine}) stay pure.
 *
 * <p><b>Idempotency.</b> The key fingerprints both canonical request fields and
 * the actual loaded snapshot. Repeating an analysis over unchanged inputs replays
 * its result, while new transactions/customer facts produce a fresh analysis.
 *
 * <p><b>XAI.</b> The stored JSON carries every reason sentence (Persian) with
 * its weight; the Phase 4 admin panel renders them verbatim, so a manager can
 * always see why a customer or group scored the way it did.
 */
public class FraudRingServiceImpl implements FraudRingService {

    private static final Logger AUDIT = Logger.getLogger("com.bank.audit.fraud-ring");

    private final FraudRingSourceRepository sourceRepository;
    private final FraudRingResultRepository resultRepository;
    private final Clock clock;

    public FraudRingServiceImpl(FraudRingSourceRepository sourceRepository,
                                FraudRingResultRepository resultRepository,
                                Clock clock) {
        this.sourceRepository = Objects.requireNonNull(sourceRepository, "sourceRepository");
        this.resultRepository = Objects.requireNonNull(resultRepository, "resultRepository");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public FraudRingAnalysis analyze(AdminPrincipal admin, FraudRingCriteria criteria) {
        AdminGuard.require(admin);
        Objects.requireNonNull(criteria, "criteria");

        // Load the current consistent snapshot before replay lookup. Request-only
        // keys would replay stale graph results after new transfers were posted.
        FraudRingSourceRepository.FraudRingSnapshot snapshot = sourceRepository.loadSnapshot(criteria);
        String idempotencyKey = idempotencyKey(criteria, snapshot);

        // ── replay path: this exact request + snapshot already has a result ────
        Optional<FraudRingAnalysis> stored = resultRepository.findByKey(idempotencyKey);
        if (stored.isPresent()) {
            AUDIT.info("RING_ANALYSIS_REPLAY admin=" + admin.username()
                    + " key=" + idempotencyKey
                    + " analysisId=" + stored.get().analysisId());
            return stored.get();
        }


        // ── in-memory analysis + scoring: NO connection is held here ──────────
        FraudRingGraph graph = FraudRingAnalyzer.analyze(
                snapshot.flows(), snapshot.phoneGroups(), snapshot.facts(), criteria);
        RiskAssessment assessment = RiskScoringEngine.assess(graph, !snapshot.truncated());

        // ── payloads: graph stats + the full scored output with XAI reasons ───
        String graphStatsJson = FraudRingPayloads.graphStats(graph, assessment, snapshot.truncated());
        String customersJson = FraudRingPayloads.topCustomers(graph, assessment);
        String groupsJson = FraudRingPayloads.topGroups(graph, assessment);

        FraudRingAnalysis analysis = new FraudRingAnalysis(0L, idempotencyKey,
                criteria.scope(), singleCustomerId(criteria), criteria.lookbackDays(),
                graphStatsJson, customersJson, groupsJson,
                admin.username(), LocalDateTime.now(clock));

        // ── store; a concurrent winner is replayed instead of duplicated ────
        long analysisId;
        try {
            analysisId = resultRepository.insert(analysis);
        } catch (FraudRingResultRepository.DuplicateAnalysisException concurrent) {
            return resultRepository.findByKey(idempotencyKey)
                    .orElseThrow(() -> new BankException(
                            "Concurrent analysis disappeared before replay: " + idempotencyKey, concurrent));
        }
        analysis = new FraudRingAnalysis(analysisId, analysis.idempotencyKey(), analysis.scope(),
                analysis.scopeCustomerId(), analysis.lookbackDays(), analysis.graphStatsJson(),
                analysis.topCustomersJson(), analysis.topGroupsJson(),
                analysis.analyzedBy(), analysis.analyzedAt());

        AUDIT.info("RING_ANALYSIS_RUN admin=" + admin.username()
                + " key=" + idempotencyKey
                + " scope=" + criteria.describeScope()
                + " period=" + criteria.describePeriod()
                + " nodes=" + graph.nodeCount()
                + " edges=" + graph.edgeCount()
                + " communities=" + graph.communities().size()
                + " cycles=" + graph.cycleCount()
                + " customersScored=" + assessment.customerCount()
                + " groupsScored=" + assessment.groupCount()
                + " maxCustomerScore=" + assessment.maxCustomerScore()
                + " truncated=" + snapshot.truncated()
                + " analysisId=" + analysisId);
        return analysis;
    }

    @Override
    public Optional<FraudRingAnalysis> findAnalysis(AdminPrincipal admin, String idempotencyKey) {
        AdminGuard.require(admin);
        if (idempotencyKey == null || idempotencyKey.isBlank()) return Optional.empty();
        Optional<FraudRingAnalysis> found = resultRepository.findByKey(idempotencyKey.trim());
        found.ifPresent(a -> AUDIT.info("RING_ANALYSIS_LOADED admin=" + admin.username()
                + " analysisId=" + a.analysisId()));
        return found;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // idempotency key: canonical fingerprint of the request
    // ─────────────────────────────────────────────────────────────────────────

    /** Public for tests: the canonical key must be stable for identical requests. */
    public static String idempotencyKey(FraudRingCriteria criteria) {
        StringJoiner canonical = new StringJoiner("|");
        canonical.add("FRAUD_RING_ANALYSIS");
        canonical.add(criteria.scope().name());
        canonical.add(criteria.customerIds().stream().sorted().map(String::valueOf)
                .reduce((a, b) -> a + "," + b).orElse(""));
        canonical.add(String.valueOf(criteria.lookbackDays()));
        canonical.add(String.valueOf(criteria.includeSharedPhone()));
        canonical.add(String.valueOf(criteria.maxNodes()));
        String key = IdempotencyUtil.fingerprint("FRAUD_RING", canonical.toString());
        IdempotencyUtil.validateKey(key);
        return key;
    }

    /** Snapshot-sensitive key used by production analysis runs. */
    public static String idempotencyKey(FraudRingCriteria criteria,
                                        FraudRingSourceRepository.FraudRingSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        String canonical = idempotencyKey(criteria) + "|" + snapshotFingerprint(snapshot);
        String key = IdempotencyUtil.fingerprint("FRAUD_RING_SNAPSHOT", canonical);
        IdempotencyUtil.validateKey(key);
        return key;
    }

    /** Stable fingerprint of the exact graph inputs consumed by the analyzer. */
    public static String snapshotFingerprint(FraudRingSourceRepository.FraudRingSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        StringBuilder canonical = new StringBuilder(1024);
        canonical.append("flowsTruncated=").append(snapshot.flowsTruncated())
                .append(";phonesTruncated=").append(snapshot.phonesTruncated())
                .append(";factsTruncated=").append(snapshot.factsTruncated()).append(';');
        snapshot.flows().stream().sorted(java.util.Comparator
                .comparingLong((com.bank.fraudring.domain.FraudRingFlow f) -> f.customer().id())
                .thenComparingLong(f -> f.counterpart().id())
                .thenComparing(com.bank.fraudring.domain.FraudRingFlow::outgoing))
                .forEach(f -> {
                    appendIdentity(canonical, "F1:", f.customer());
                    appendIdentity(canonical, "F2:", f.counterpart());
                    canonical.append("FD:").append(f.outgoing()).append(':')
                            .append(f.transferCount()).append(':').append(f.totalAmount().toPlainString()).append(':')
                            .append(f.lastActivity()).append(';');
                });
        snapshot.phoneGroups().entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    canonical.append("P:").append(entry.getKey()).append(':');
                    entry.getValue().stream().sorted(java.util.Comparator
                            .comparingLong(com.bank.graph.domain.CustomerIdentity::id))
                            .forEach(identity -> appendIdentity(canonical, "PM:", identity));
                    canonical.append(';');
                });
        snapshot.facts().entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    com.bank.fraudring.domain.CustomerRiskFact fact = entry.getValue();
                    canonical.append("C:").append(entry.getKey()).append(':')
                            .append(fact.accountCount()).append(':')
                            .append(fact.newestAccountAgeDays()).append(':')
                            .append(fact.sharedPhoneCount()).append(';');
                });
        return IdempotencyUtil.fingerprint("FRAUD_RING_INPUTS", canonical.toString());
    }

    private static void appendIdentity(StringBuilder target, String prefix,
                                       com.bank.graph.domain.CustomerIdentity identity) {
        String name = identity.displayName();
        target.append(prefix).append(identity.id()).append(':').append(identity.banned()).append(':')
                .append(name.length()).append(':').append(name).append(';');
    }

    private static Long singleCustomerId(FraudRingCriteria criteria) {
        return criteria.scope() == com.bank.fraudring.domain.FraudRingScope.SINGLE
                ? criteria.customerIds().iterator().next() : null;
    }
}
