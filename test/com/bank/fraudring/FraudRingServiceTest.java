package com.bank.fraudring;

import com.bank.exception.AccessDeniedException;
import com.bank.fraudring.application.FraudRingService;
import com.bank.fraudring.application.FraudRingServiceImpl;
import com.bank.fraudring.application.FraudRingSourceRepository;
import com.bank.fraudring.domain.CustomerRiskFact;
import com.bank.fraudring.domain.FraudRingAnalysis;
import com.bank.fraudring.domain.FraudRingCriteria;
import com.bank.fraudring.domain.FraudRingFlow;
import com.bank.graph.domain.CustomerIdentity;
import com.bank.security.AdminPrincipal;
import com.bank.testkit.TestAdmin;
import com.bank.testkit.TestKit;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Service-level tests with in-memory fakes: authorisation, snapshot-sensitive
 * idempotency, audit fields, and key fingerprint stability. No database needed.
 */
public final class FraudRingServiceTest {

    /** Fake source repository: returns canned snapshots (countable). */
    private static class FakeSource implements FraudRingSourceRepository {
        FraudRingSnapshot next = new FraudRingSnapshot(List.of(), false, Map.of(), false, Map.of(), false);
        int loads;

        @Override
        public FraudRingSnapshot loadSnapshot(FraudRingCriteria criteria) {
            loads++;
            return next;
        }
    }

    /** Fake result repository: idempotency + duplicate concurrency semantics (plain port fake). */
    private static final class FakeResult implements com.bank.fraudring.application.FraudRingResultRepository {
        private final Map<String, FraudRingAnalysis> rows = new LinkedHashMap<>();
        private final AtomicInteger inserts = new AtomicInteger();
        private long nextId = 1;
        /** When true, insert() mimics a concurrent winner having stored the key first. */
        boolean raceOnInsert;

        @Override
        public long insert(FraudRingAnalysis analysis) {
            if (rows.containsKey(analysis.idempotencyKey())) {
                throw new com.bank.fraudring.application.FraudRingResultRepository
                        .DuplicateAnalysisException(analysis.idempotencyKey(), null);
            }
            if (raceOnInsert) {
                // simulate the winner writing between our read and insert
                rows.put(analysis.idempotencyKey(), withId(analysis, 999L));
                throw new com.bank.fraudring.application.FraudRingResultRepository
                        .DuplicateAnalysisException(analysis.idempotencyKey(), null);
            }
            long id = nextId++;
            rows.put(analysis.idempotencyKey(), withId(analysis, id));
            inserts.incrementAndGet();
            return id;
        }

        @Override
        public Optional<FraudRingAnalysis> findByKey(String key) {
            return Optional.ofNullable(rows.get(key));
        }

        private static FraudRingAnalysis withId(FraudRingAnalysis a, long id) {
            return new FraudRingAnalysis(id, a.idempotencyKey(), a.scope(), a.scopeCustomerId(),
                    a.lookbackDays(), a.graphStatsJson(), a.topCustomersJson(), a.topGroupsJson(),
                    a.analyzedBy(), a.analyzedAt());
        }
    }

    public static void main(String[] args) {
        AdminPrincipal admin = TestAdmin.principal();

        TestKit.section("[1] authorisation");
        nullPrincipalIsRejected(admin);

        TestKit.section("[2] idempotency");
        identicalRequestReplaysStoredRow(admin);
        changedSnapshotGetsNewRow(admin);
        differentRequestGetsNewRow(admin);
        concurrentWinnerIsReplayedNotDuplicated(admin);

        TestKit.section("[3] fingerprint stability");
        keyIsStableForIdenticalRequests();
        keyChangesWithEachCanonicalField();

        TestKit.section("[4] result contract");
        analysisRowCarriesScopeAndAudit(admin);
        graphStatsJsonIsStructural(admin);
        payloadsCarryScoredOutput(admin);

        TestKit.finish("FraudRingServiceTest");
    }

    // ─────────────────────────── tests ───────────────────────────

    static void nullPrincipalIsRejected(AdminPrincipal admin) {
        FakeSource source = new FakeSource();
        FakeResult results = new FakeResult();
        FraudRingService service = new FraudRingServiceImpl(source, results, clock());
        TestKit.checkThrows("analyze(null) is denied", AccessDeniedException.class,
                () -> service.analyze(null, FraudRingCriteria.full()));
        TestKit.checkThrows("findAnalysis(null principal) is denied", AccessDeniedException.class,
                () -> service.findAnalysis(null, "abc"));
        // the guard fires BEFORE any repository work
        TestKit.checkEquals("no snapshot was loaded", 0, source.loads);
    }

    static void identicalRequestReplaysStoredRow(AdminPrincipal admin) {
        FakeSource source = new FakeSource();
        source.next = snapshot();                        // a real ring 1→2→3→1
        FakeResult results = new FakeResult();
        FraudRingServiceImpl service = new FraudRingServiceImpl(source, results, clock());

        FraudRingAnalysis first = service.analyze(admin, FraudRingCriteria.full());
        FraudRingAnalysis second = service.analyze(admin, FraudRingCriteria.full());

        TestKit.checkEquals("replay returns the same analysis id",
                first.analysisId(), second.analysisId());
        TestKit.checkEquals("exactly ONE row inserted", 1, results.inserts.get());
        TestKit.checkEquals("current snapshot is reloaded before replay lookup", 2,
                source.loads);
        TestKit.check("replay carries the same graph stats",
                first.graphStatsJson().equals(second.graphStatsJson()));
    }


    static void changedSnapshotGetsNewRow(AdminPrincipal admin) {
        FakeSource source = new FakeSource();
        source.next = snapshot();
        FakeResult results = new FakeResult();
        FraudRingServiceImpl service = new FraudRingServiceImpl(source, results, clock());
        FraudRingAnalysis first = service.analyze(admin, FraudRingCriteria.full());

        FraudRingSourceRepository.FraudRingSnapshot original = source.next;
        List<FraudRingFlow> changedFlows = new java.util.ArrayList<>(original.flows());
        FraudRingFlow firstFlow = changedFlows.get(0);
        changedFlows.set(0, new FraudRingFlow(firstFlow.customer(), firstFlow.counterpart(),
                firstFlow.outgoing(), firstFlow.transferCount() + 1,
                firstFlow.totalAmount().add(new BigDecimal("25.00")), firstFlow.lastActivity()));
        source.next = new FraudRingSourceRepository.FraudRingSnapshot(changedFlows,
                original.flowsTruncated(), original.phoneGroups(), original.phonesTruncated(),
                original.facts(), original.factsTruncated());

        FraudRingAnalysis second = service.analyze(admin, FraudRingCriteria.full());
        TestKit.check("same criteria but changed graph inputs get a new key",
                !first.idempotencyKey().equals(second.idempotencyKey()));
        TestKit.check("new graph snapshot creates a new analysis row", first.analysisId() != second.analysisId());
        TestKit.checkEquals("both snapshots persisted", 2, results.inserts.get());
    }

    static void differentRequestGetsNewRow(AdminPrincipal admin) {
        FakeSource source = new FakeSource();
        source.next = snapshot();
        FakeResult results = new FakeResult();
        FraudRingServiceImpl service = new FraudRingServiceImpl(source, results, clock());

        FraudRingAnalysis allTime = service.analyze(admin, FraudRingCriteria.full());
        FraudRingAnalysis last30 = service.analyze(admin,
                new FraudRingCriteria(FraudRingCriteria.full().scope(),
                        FraudRingCriteria.full().customerIds(), 30, true, 500));

        TestKit.check("different canonical request → different key",
                !allTime.idempotencyKey().equals(last30.idempotencyKey()));
        TestKit.checkEquals("two rows stored", 2, results.inserts.get());
        TestKit.check("ids differ", allTime.analysisId() != last30.analysisId());
    }

    static void concurrentWinnerIsReplayedNotDuplicated(AdminPrincipal admin) {
        FakeSource source = new FakeSource();
        source.next = snapshot();
        FakeResult results = new FakeResult();
        results.raceOnInsert = true;                     // winner stores the key first
        FraudRingServiceImpl service = new FraudRingServiceImpl(source, results, clock());

        FraudRingAnalysis raced = service.analyze(admin, FraudRingCriteria.full());
        TestKit.checkEquals("raced insert replays the winner's row", 999L, raced.analysisId());
        TestKit.checkEquals("loser never counted its own insert", 0, results.inserts.get());
    }

    static void keyIsStableForIdenticalRequests() {
        FraudRingCriteria a = new FraudRingCriteria(FraudRingCriteria.subset(Set.of(3L, 1L, 2L)).scope(),
                Set.of(3L, 1L, 2L), null, true, 2000);
        FraudRingCriteria b = new FraudRingCriteria(FraudRingCriteria.subset(Set.of(1L, 2L, 3L)).scope(),
                Set.of(1L, 2L, 3L), null, true, 2000);
        TestKit.checkEquals("set order does not change the fingerprint",
                FraudRingServiceImpl.idempotencyKey(a),
                FraudRingServiceImpl.idempotencyKey(b));
        TestKit.checkEquals("key is 64 hex chars (SHA-256)",
                64, FraudRingServiceImpl.idempotencyKey(a).length());
    }

    static void keyChangesWithEachCanonicalField() {
        String base = FraudRingServiceImpl.idempotencyKey(FraudRingCriteria.full());
        TestKit.check("lookback changes the key", !base.equals(
                FraudRingServiceImpl.idempotencyKey(new FraudRingCriteria(
                        FraudRingCriteria.full().scope(), Set.of(), 7, true, 2000))));
        TestKit.check("shared-phone flag changes the key", !base.equals(
                FraudRingServiceImpl.idempotencyKey(new FraudRingCriteria(
                        FraudRingCriteria.full().scope(), Set.of(), null, false, 2000))));
        TestKit.check("node budget changes the key", !base.equals(
                FraudRingServiceImpl.idempotencyKey(new FraudRingCriteria(
                        FraudRingCriteria.full().scope(), Set.of(), null, true, 2001))));
        TestKit.check("scope changes the key", !base.equals(
                FraudRingServiceImpl.idempotencyKey(FraudRingCriteria.subset(Set.of(1L)))));
    }

    static void analysisRowCarriesScopeAndAudit(AdminPrincipal admin) {
        FakeSource source = new FakeSource();
        source.next = snapshot();
        FakeResult results = new FakeResult();
        FraudRingServiceImpl service = new FraudRingServiceImpl(source, results, clock());

        FraudRingAnalysis row = service.analyze(admin, FraudRingCriteria.single(42));
        TestKit.checkEquals("SINGLE scope recorded", "SINGLE", row.scope().name());
        TestKit.checkEquals("scope customer recorded", 42L, row.scopeCustomerId());
        TestKit.checkEquals("analyst recorded", TestAdmin.USERNAME, row.analyzedBy());
        TestKit.check("analysis time present", row.analyzedAt() != null);
        TestKit.check("lookback null for all-time", row.lookbackDays() == null);

        Optional<FraudRingAnalysis> loaded = service.findAnalysis(admin, row.idempotencyKey());
        TestKit.check("findAnalysis replays the row by key", loaded.isPresent());
        TestKit.checkEquals("same id on replay", row.analysisId(), loaded.orElseThrow().analysisId());
    }

    static void graphStatsJsonIsStructural(AdminPrincipal admin) {
        FakeSource source = new FakeSource();
        source.next = snapshot();                       // ring 1→2→3→1
        FakeResult results = new FakeResult();
        FraudRingServiceImpl service = new FraudRingServiceImpl(source, results, clock());
        FraudRingAnalysis row = service.analyze(admin, FraudRingCriteria.full());

        String json = row.graphStatsJson();
        TestKit.check("stats carry the node count", json.contains("\"nodeCount\":3"));
        TestKit.check("stats carry the edge count", json.contains("\"edgeCount\":3"));
        TestKit.check("stats carry the community count", json.contains("\"communityCount\":1"));
        TestKit.check("stats carry the cycle count", json.contains("\"cycleCount\":1"));
        TestKit.check("stats record all-time scope", json.contains("\"scope\":\"FULL\""));
        TestKit.check("stats disclose insufficient unsupervised baseline",
                json.contains("\"anomalyModel\":\"INSUFFICIENT_BASELINE\""));
        TestKit.check("stats disclose cycle-enumeration completeness",
                json.contains("\"cycleDetectionTruncated\":false"));
        TestKit.check("no truncation on the tiny fixture", json.contains("\"truncated\":false"));
        TestKit.check("graph stats are phase 3", json.contains("\"phase\":3"));
    }

    static void payloadsCarryScoredOutput(AdminPrincipal admin) {
        TestKit.section("[4b] the stored payloads carry the scored output");
        FakeSource source = new FakeSource();
        source.next = snapshot();                       // ring 1→2→3→1 + phone 1↔3
        FakeResult results = new FakeResult();
        FraudRingServiceImpl service = new FraudRingServiceImpl(source, results, clock());
        FraudRingAnalysis row = service.analyze(admin, FraudRingCriteria.full());

        String customers = row.topCustomersJson();
        TestKit.check("customers payload is phase 3", customers.contains("\"phase\":3"));
        TestKit.check("all three customers scored",
                customers.contains("\"customersScored\":3"));
        TestKit.check("band counts present", customers.contains("\"bandCounts\":{"));
        TestKit.check("ring member 1 carries MONEY_CYCLE",
                customers.contains("\"code\":\"MONEY_CYCLE\""));
        TestKit.check("phone sharers 1 and 3 carry SHARED_PHONE",
                customers.contains("\"code\":\"SHARED_PHONE\""));
        TestKit.check("Persian reason text survives to the payload",
                customers.contains("حلقه"));
        TestKit.check("score fields present", customers.contains("\"score\":"));

        String groups = row.topGroupsJson();
        TestKit.check("groups payload is phase 3", groups.contains("\"phase\":3"));
        TestKit.check("the single community scored",
                groups.contains("\"groupsScored\":1"));
        TestKit.check("group carries GROUP_CYCLE",
                groups.contains("\"code\":\"GROUP_CYCLE\""));
        TestKit.check("group carries GROUP_SHARED_PHONE (1 and 3 share the phone)",
                groups.contains("\"code\":\"GROUP_SHARED_PHONE\""));
        TestKit.check("group members listed", groups.contains("\"members\":[1,2,3]"));
    }

    // ─────────────────────────── fixtures ───────────────────────────

    /** A classic laundering ring: 1→2→3→1 with a shared phone between 1 and 3. */
    private static FraudRingSourceRepository.FraudRingSnapshot snapshot() {
        LocalDateTime when = LocalDateTime.of(2026, 11, 1, 9, 0);
        List<FraudRingFlow> flows = List.of(
                new FraudRingFlow(CustomerIdentity.of(1, "A", "One", false),
                        CustomerIdentity.of(2, "B", "Two", false), true, 2,
                        new BigDecimal("1200.00"), when),
                new FraudRingFlow(CustomerIdentity.of(2, "B", "Two", false),
                        CustomerIdentity.of(3, "C", "Three", false), true, 1,
                        new BigDecimal("1100.00"), when.plusHours(1)),
                new FraudRingFlow(CustomerIdentity.of(3, "C", "Three", false),
                        CustomerIdentity.of(1, "A", "One", false), true, 1,
                        new BigDecimal("1000.00"), when.plusHours(2)));
        Map<String, List<CustomerIdentity>> phones = Map.of("+15550001",
                List.of(CustomerIdentity.of(1, "A", "One", false),
                        CustomerIdentity.of(3, "C", "Three", false)));
        Map<Long, CustomerRiskFact> facts = Map.of(
                1L, new CustomerRiskFact(1, 1, 25, 1),
                2L, new CustomerRiskFact(2, 1, 400, 0),
                3L, new CustomerRiskFact(3, 2, 10, 1));
        return new FraudRingSourceRepository.FraudRingSnapshot(
                flows, false, phones, false, facts, false);
    }

    private static java.time.Clock clock() {
        return java.time.Clock.fixed(java.time.Instant.parse("2026-11-17T12:00:00Z"),
                java.time.ZoneOffset.UTC);
    }
}
