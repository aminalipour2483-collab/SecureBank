package com.bank.fraudring;

import com.bank.fraudring.domain.CentralityMeasures;
import com.bank.fraudring.domain.Community;
import com.bank.fraudring.domain.CustomerRiskFact;
import com.bank.fraudring.domain.CustomerRiskScore;
import com.bank.fraudring.domain.FraudRingAnalyzer;
import com.bank.fraudring.domain.FraudRingCriteria;
import com.bank.fraudring.domain.FraudRingFlow;
import com.bank.fraudring.domain.FraudRingGraph;
import com.bank.fraudring.domain.GroupRiskScore;
import com.bank.fraudring.domain.RiskAssessment;
import com.bank.fraudring.domain.RiskBand;
import com.bank.fraudring.domain.RiskScoringEngine;
import com.bank.fraudring.domain.ScoredReason;
import com.bank.graph.domain.CustomerIdentity;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.bank.testkit.TestKit;

import static com.bank.testkit.TestKit.check;
import static com.bank.testkit.TestKit.checkEquals;
import static com.bank.testkit.TestKit.section;
import static com.bank.testkit.TestKit.finish;

/**
 * Phase 3 scoring-engine tests on hand-built graphs: weights, bands, the XAI
 * contract (reasons sum to the score), Persian reason text, determinism and
 * the 100-point cap. Same style as FraudRingAnalyzerTest — pure domain, no JDBC.
 */
public final class FraudRingScoringTest {

    public static void main(String[] args) {
        riskBandMapping();
        cleanCustomerScoresZero();
        moneyCycleScoresThirtyFive();
        fullKitchenSinksToCap();
        sharedPhoneScoresFifteen();
        youngAccountScoresTen();
        bannedScoresTen();
        velocityScoresTen();
        groupCycleScoresForty();
        groupVolumeDensityPhonesBannedSize();
        groupIsMoreThanMaxMember();
        deterministicScoring();
        unsupervisedGraphModelActivatesWithEnoughPeers();
        truncatedSnapshotDisablesUnsupervisedModel();
        reasonValidation();
        finish("FraudRingScoringTest");
    }

    // ─────────────────────────── fixtures ───────────────────────────

    private static CustomerIdentity id(long n, boolean banned) {
        return CustomerIdentity.of(n, "C" + n, "Test", banned);
    }

    private static FraudRingFlow flow(long from, long to, long count, String amount) {
        return new FraudRingFlow(id(from, false), id(to, false), true, count,
                new BigDecimal(amount), LocalDateTime.of(2026, 1, 15, 10, 0));
    }

    private static RiskAssessment run(List<FraudRingFlow> flows,
                                      Map<String, List<CustomerIdentity>> phones,
                                      Map<Long, CustomerRiskFact> facts) {
        FraudRingGraph graph = FraudRingAnalyzer.analyze(flows, phones, facts, FraudRingCriteria.full());
        return RiskScoringEngine.assess(graph);
    }

    private static Map<Long, CustomerRiskFact> factsOf(FraudRingFlow... flows) {
        Map<Long, CustomerRiskFact> facts = new LinkedHashMap<>();
        for (FraudRingFlow f : flows) {
            facts.putIfAbsent(f.customer().id(), CustomerRiskFact.unknown(f.customer().id()));
            facts.putIfAbsent(f.counterpart().id(), CustomerRiskFact.unknown(f.counterpart().id()));
        }
        return facts;
    }

    // ─────────────────────────── tests ───────────────────────────

    static void riskBandMapping() {
        section("[1] band mapping mirrors the transaction engine");
        checkEquals("0 → LOW", RiskBand.LOW, RiskBand.of(0));
        checkEquals("24 → LOW", RiskBand.LOW, RiskBand.of(24));
        checkEquals("25 → MEDIUM", RiskBand.MEDIUM, RiskBand.of(25));
        checkEquals("49 → MEDIUM", RiskBand.MEDIUM, RiskBand.of(49));
        checkEquals("50 → HIGH", RiskBand.HIGH, RiskBand.of(50));
        checkEquals("74 → HIGH", RiskBand.HIGH, RiskBand.of(74));
        checkEquals("75 → CRITICAL", RiskBand.CRITICAL, RiskBand.of(75));
        checkEquals("100 → CRITICAL", RiskBand.CRITICAL, RiskBand.of(100));
        TestKit.checkThrows("101 rejected", IllegalArgumentException.class,
                () -> RiskBand.of(101));
        TestKit.checkThrows("-1 rejected", IllegalArgumentException.class,
                () -> RiskBand.of(-1));
        check("every band has a Persian label",
                List.of(RiskBand.values()).stream().allMatch(b -> !b.persianLabel().isBlank()));
    }

    static void cleanCustomerScoresZero() {
        section("[2] a clean pair scores zero");
        List<FraudRingFlow> flows = List.of(flow(1, 2, 1, "50.00"));
        RiskAssessment assessment = run(flows, Map.of(), factsOf(flows.get(0)));
        CustomerRiskScore c1 = assessment.customer(1);
        checkEquals("one transfer, no signals → 0", 0, c1.score());
        checkEquals("band LOW", RiskBand.LOW, c1.band());
        check("no reasons", c1.reasons().isEmpty());
        checkEquals("too few customers disables graph Isolation Forest",
                com.bank.fraudring.domain.GraphIsolationForest.Status.INSUFFICIENT_BASELINE,
                assessment.anomalyModelStatus());
    }

    static void moneyCycleScoresThirtyFive() {
        section("[3] MONEY_CYCLE +35");
        List<FraudRingFlow> flows = List.of(
                flow(1, 2, 1, "400.00"), flow(2, 3, 1, "350.00"), flow(3, 1, 1, "300.00"));
        RiskAssessment assessment = run(flows, Map.of(), factsOf(flows.toArray(FraudRingFlow[]::new)));
        CustomerRiskScore c1 = assessment.customer(1);
        checkEquals("ring member scores 35", 35, c1.score());
        checkEquals("band MEDIUM", RiskBand.MEDIUM, c1.band());
        checkEquals("exactly one reason", 1, c1.reasons().size());
        checkEquals("reason is MONEY_CYCLE", "MONEY_CYCLE", c1.reasons().get(0).code());
        checkEquals("reason weight 35", 35, c1.reasons().get(0).points());
        check("Persian detail mentions the ring count",
                c1.reasons().get(0).persianDetail().contains("۱ حلقه"));
        check("Persian detail carries the ring total",
                c1.reasons().get(0).persianDetail().contains("1050.00"));
    }

    static void fullKitchenSinksToCap() {
        section("[4] overlapping signals reach HIGH/CRITICAL territory");
        // courier 9 bridges two cliques AND sits on a cycle AND shares a phone
        List<FraudRingFlow> flows = new ArrayList<>(List.of(
                flow(1, 2, 5, "500.00"), flow(2, 1, 5, "500.00"),
                flow(2, 3, 5, "500.00"), flow(3, 2, 5, "500.00"),
                flow(1, 3, 5, "500.00"), flow(3, 1, 5, "500.00"),
                flow(1, 9, 2, "40.00"), flow(9, 1, 2, "40.00"),   // 9 on a 2-cycle with 1
                flow(9, 4, 1, "10.00"), flow(4, 5, 1, "10.00"), flow(5, 6, 1, "10.00"),
                flow(6, 4, 1, "10.00")));
        Map<String, List<CustomerIdentity>> phones = Map.of("+15550009",
                List.of(id(9, false), id(4, false)));
        Map<Long, CustomerRiskFact> facts = factsOf(flows.toArray(FraudRingFlow[]::new));
        facts.put(9L, new CustomerRiskFact(9, 1, 7, 1));      // young account + shared phone
        RiskAssessment assessment = run(flows, phones, facts);
        CustomerRiskScore c9 = assessment.customer(9);
        // cycle 35 + betweenness 20 + shared phone 15 + young 10 + pagerank/courier as earned
        check("overlapping signals reach at least 80",
                c9.score() >= 80, "score=" + c9.score());
        check("score never exceeds 100", c9.score() <= 100);
        check("every score is a capped sum of reasons",
                c9.reasons().stream().mapToInt(ScoredReason::points).sum() >= c9.score());
        checkEquals("top of the ranking is customer 9",
                9L, assessment.customers().get(0).customerId());
        check("ranking is score-descending",
                isDescending(assessment.customers().stream().map(CustomerRiskScore::score).toList()));
    }

    private static boolean isDescending(List<Integer> values) {
        for (int i = 1; i < values.size(); i++) {
            if (values.get(i - 1) < values.get(i)) return false;
        }
        return true;
    }

    static void sharedPhoneScoresFifteen() {
        section("[5] SHARED_PHONE +15");
        // 1 and 2 share a phone but have a single innocent transfer each
        List<FraudRingFlow> flows = List.of(flow(1, 3, 1, "20.00"), flow(2, 4, 1, "20.00"));
        Map<String, List<CustomerIdentity>> phones = Map.of("+15550001",
                List.of(id(1, false), id(2, false)));
        Map<Long, CustomerRiskFact> facts = new LinkedHashMap<>();
        for (long i = 1; i <= 4; i++) facts.put(i, CustomerRiskFact.unknown(i));
        facts.put(1L, new CustomerRiskFact(1, 1, 400, 1));   // phone count folded by hand here
        facts.put(2L, new CustomerRiskFact(2, 1, 400, 1));
        RiskAssessment assessment = run(flows, phones, facts);
        checkEquals("phone sharer scores 15", 15, assessment.customer(1).score());
        checkEquals("reason is SHARED_PHONE", "SHARED_PHONE",
                assessment.customer(1).reasons().get(0).code());
        check("Persian detail counts the shared numbers",
                assessment.customer(1).reasons().get(0).persianDetail().contains("۱"));
    }

    static void youngAccountScoresTen() {
        section("[6] YOUNG_ACCOUNTS +10");
        List<FraudRingFlow> flows = List.of(flow(1, 2, 1, "30.00"));
        Map<Long, CustomerRiskFact> facts = factsOf(flows.get(0));
        facts.put(1L, new CustomerRiskFact(1, 1, 10, 0));    // 10 days old
        RiskAssessment assessment = run(flows, Map.of(), facts);
        checkEquals("young account scores 10", 10, assessment.customer(1).score());
        checkEquals("reason is YOUNG_ACCOUNTS", "YOUNG_ACCOUNTS",
                assessment.customer(1).reasons().get(0).code());
        // 29 days is still young; 30 is not
        Map<Long, CustomerRiskFact> facts30 = factsOf(flows.get(0));
        facts30.put(1L, new CustomerRiskFact(1, 1, 30, 0));
        checkEquals("30 days is NOT young", 0, run(flows, Map.of(), facts30).customer(1).score());
    }

    static void bannedScoresTen() {
        section("[7] BANNED_MEMBER +10");
        FraudRingFlow f = new FraudRingFlow(id(1, true), id(2, false), true, 1,
                new BigDecimal("30.00"), null);
        Map<Long, CustomerRiskFact> facts = factsOf(f);
        RiskAssessment assessment = run(List.of(f), Map.of(), facts);
        checkEquals("banned customer scores 10", 10, assessment.customer(1).score());
        checkEquals("reason is BANNED_MEMBER", "BANNED_MEMBER",
                assessment.customer(1).reasons().get(0).code());
    }

    static void velocityScoresTen() {
        section("[8] VELOCITY +10");
        List<FraudRingFlow> flows = List.of(flow(1, 2, 10, "500.00"));   // 10 transfers on one pair
        Map<Long, CustomerRiskFact> facts = factsOf(flows.get(0));
        RiskAssessment assessment = run(flows, Map.of(), facts);
        checkEquals("10 transfers on one pair scores 10", 10, assessment.customer(1).score());
        checkEquals("reason is VELOCITY", "VELOCITY",
                assessment.customer(1).reasons().get(0).code());
        // 9 transfers stays clean
        List<FraudRingFlow> slow = List.of(flow(1, 2, 9, "500.00"));
        checkEquals("9 transfers is clean", 0,
                run(slow, Map.of(), factsOf(slow.get(0))).customer(1).score());
    }

    static void groupCycleScoresForty() {
        section("[9] GROUP_CYCLE +40 (a triangle ring is also dense)");
        List<FraudRingFlow> ring = List.of(
                flow(1, 2, 1, "100.00"), flow(2, 3, 1, "100.00"), flow(3, 1, 1, "100.00"));
        RiskAssessment assessment = run(ring, Map.of(), factsOf(ring.toArray(FraudRingFlow[]::new)));
        checkEquals("one group scored", 1, assessment.groupCount());
        GroupRiskScore group = assessment.groups().get(0);
        // a closed triangle has density 1.0 → GROUP_CYCLE 40 + GROUP_DENSITY 15 = 55
        checkEquals("ring group scores 55 (cycle + clique density)", 55, group.score());
        checkEquals("band HIGH", RiskBand.HIGH, group.band());
        checkEquals("top reason is GROUP_CYCLE", "GROUP_CYCLE", group.reasons().get(0).code());
        check("all three members recorded", group.memberIds().containsAll(List.of(1L, 2L, 3L)));
        // a lone two-customer pair IS maximally dense (the one possible edge exists),
        // so it fires GROUP_DENSITY +15 — with no cycle, volume or size, that is all:
        List<FraudRingFlow> pair = List.of(flow(1, 2, 1, "100.00"));
        RiskAssessment pairAssessment = run(pair, Map.of(), factsOf(pair.get(0)));
        checkEquals("a lone pair group fires density only (score 15)", 15,
                pairAssessment.groups().get(0).score());
        checkEquals("its only reason is GROUP_DENSITY", "GROUP_DENSITY",
                pairAssessment.groups().get(0).reasons().get(0).code());
    }

    static void groupVolumeDensityPhonesBannedSize() {
        section("[10] group volume/density/phone/banned/size weights");
        // K5 clique: 5 members, all pairs, high volume, dense
        List<FraudRingFlow> clique = new ArrayList<>();
        for (long a = 1; a <= 5; a++) {
            for (long b = 1; b <= 5; b++) {
                if (a != b) clique.add(flow(a, b, 4, "40000.00"));
            }
        }
        Map<String, List<CustomerIdentity>> phones = Map.of("+15550005",
                List.of(id(1, false), id(2, false)));
        Map<Long, CustomerRiskFact> facts = new LinkedHashMap<>();
        for (long i = 1; i <= 5; i++) facts.put(i, CustomerRiskFact.unknown(i));
        // member 1 banned: rebuild flows with banned identity for 1
        List<FraudRingFlow> cliqueBanned = new ArrayList<>();
        for (FraudRingFlow f : clique) {
            cliqueBanned.add(new FraudRingFlow(
                    f.customer().id() == 1 ? id(1, true) : f.customer(),
                    f.counterpart().id() == 1 ? id(1, true) : f.counterpart(),
                    f.outgoing(), f.transferCount(), f.totalAmount(), f.lastActivity()));
        }
        RiskAssessment assessment = run(cliqueBanned, phones, facts);
        GroupRiskScore group = assessment.groups().get(0);
        List<String> codes = group.reasons().stream().map(ScoredReason::code).toList();
        check("GROUP_VOLUME fired (internal volume 800,000 ≥ 100,000)",
                codes.contains("GROUP_VOLUME"));
        check("GROUP_DENSITY fired (clique density 1.0)",
                codes.contains("GROUP_DENSITY"));
        check("GROUP_SHARED_PHONE fired", codes.contains("GROUP_SHARED_PHONE"));
        check("GROUP_BANNED fired", codes.contains("GROUP_BANNED"));
        check("GROUP_SIZE fired (5 members)", codes.contains("GROUP_SIZE"));
        // GROUP_CYCLE 40 + VOLUME 25 + DENSITY 15 + PHONE 15 + BANNED 10 + SIZE 10 = 115 → capped 100
        checkEquals("six weights capped at 100", 100, group.score());
        checkEquals("band CRITICAL", RiskBand.CRITICAL, group.band());
        check("XAI: reasons sum is at least the capped score",
                group.reasons().stream().mapToInt(ScoredReason::points).sum() >= group.score());
    }

    static void groupIsMoreThanMaxMember() {
        section("[11] a group is scored on GROUP facts, not max-member");
        // innocent pair (no cycles, tiny amounts) inside a dense money-cycling triangle:
        // the triangle group must outscore any individual member's own score
        List<FraudRingFlow> flows = List.of(
                flow(1, 2, 1, "100.00"), flow(2, 3, 1, "100.00"), flow(3, 1, 1, "100.00"),
                flow(4, 5, 1, "5.00"));
        RiskAssessment assessment = run(flows, Map.of(), factsOf(flows.toArray(FraudRingFlow[]::new)));
        int anyMember = assessment.customers().stream()
                .filter(c -> c.customerId() == 1 || c.customerId() == 2 || c.customerId() == 3)
                .mapToInt(CustomerRiskScore::score).max().orElse(0);
        int groupScore = assessment.groups().get(0).score();
        check("the ring group outscores its members' own scores",
                groupScore > anyMember, "group=" + groupScore + " member=" + anyMember);
    }

    static void deterministicScoring() {
        section("[12] scoring is deterministic");
        List<FraudRingFlow> flows = List.of(
                flow(1, 2, 5, "500.00"), flow(2, 1, 5, "500.00"),
                flow(2, 3, 2, "80.00"), flow(3, 2, 2, "80.00"),
                flow(3, 9, 1, "40.00"), flow(9, 3, 1, "40.00"),
                flow(4, 5, 1, "20.00"), flow(5, 4, 1, "20.00"));
        Map<String, List<CustomerIdentity>> phones = Map.of("+15550009",
                List.of(id(9, false), id(4, false)));
        Map<Long, CustomerRiskFact> facts = new LinkedHashMap<>();
        for (long i = 1; i <= 9; i++) {
            if (i != 6 && i != 7 && i != 8) facts.put(i, new CustomerRiskFact(i, 1, (int) (i * 30), i == 9 ? 1 : 0));
        }
        RiskAssessment a = run(flows, phones, facts);
        RiskAssessment b = run(flows, phones, facts);
        checkEquals("customer scores identical",
                a.customers().stream().map(c -> c.customerId() + ":" + c.score()).toList(),
                b.customers().stream().map(c -> c.customerId() + ":" + c.score()).toList());
        checkEquals("group scores identical",
                a.groups().stream().map(g -> g.communityId() + ":" + g.score()).toList(),
                b.groups().stream().map(g -> g.communityId() + ":" + g.score()).toList());
        checkEquals("Persian reason text identical",
                a.customers().stream().flatMap(c -> c.reasons().stream().map(ScoredReason::persianDetail)).toList(),
                b.customers().stream().flatMap(c -> c.reasons().stream().map(ScoredReason::persianDetail)).toList());
    }


    static void unsupervisedGraphModelActivatesWithEnoughPeers() {
        section("[11] unsupervised graph anomaly model");
        List<FraudRingFlow> flows = new ArrayList<>();
        for (long id = 1; id <= 40; id++) {
            long next = id == 40 ? 1 : id + 1;
            flows.add(flow(id, next, id == 1 ? 1_000 : 1,
                    id == 1 ? "1000000.00" : "100.00"));
        }
        RiskAssessment assessment = run(flows, Map.of(), factsOf(flows.toArray(FraudRingFlow[]::new)));
        checkEquals("Isolation Forest active with 40 customers",
                com.bank.fraudring.domain.GraphIsolationForest.Status.ACTIVE,
                assessment.anomalyModelStatus());
        checkEquals("all 40 customers form the peer baseline", 40, assessment.anomalyBaselineSize());
        CustomerRiskScore unusual = assessment.customer(1);
        check("structural outlier receives an explainable graph-anomaly reason",
                unusual.reasons().stream().anyMatch(r -> r.code().equals("GRAPH_ANOMALY")),
                "reasons=" + unusual.reasonCodes());
        check("model explanation explicitly says score is not fraud probability",
                unusual.reasons().stream().filter(r -> r.code().equals("GRAPH_ANOMALY"))
                        .allMatch(r -> r.persianDetail().contains("احتمال تقلب نیست")));
    }

    static void truncatedSnapshotDisablesUnsupervisedModel() {
        List<FraudRingFlow> flows = new ArrayList<>();
        for (long id = 1; id <= 40; id++) {
            long next = id == 40 ? 1 : id + 1;
            flows.add(flow(id, next, 1, "100.00"));
        }
        FraudRingGraph graph = FraudRingAnalyzer.analyze(flows, Map.of(),
                factsOf(flows.toArray(FraudRingFlow[]::new)), FraudRingCriteria.full());
        RiskAssessment assessment = RiskScoringEngine.assess(graph, false);
        checkEquals("truncated source snapshot disables peer anomaly scoring",
                com.bank.fraudring.domain.GraphIsolationForest.Status.INCOMPLETE_SNAPSHOT,
                assessment.anomalyModelStatus());
        check("no graph-anomaly reason is emitted from incomplete baseline",
                assessment.customers().stream().noneMatch(c -> c.reasonCodes().contains("GRAPH_ANOMALY")));
    }

    static void reasonValidation() {
        section("[13] XAI contract on the record types");
        TestKit.checkThrows("negative points rejected", IllegalArgumentException.class,
                () -> new ScoredReason("X", "t", -1, "d"));
        TestKit.checkThrows("blank code rejected", IllegalArgumentException.class,
                () -> new ScoredReason("", "t", 1, "d"));
        TestKit.checkThrows("mismatched band rejected", IllegalArgumentException.class,
                () -> new CustomerRiskScore(1, "n", 80, RiskBand.LOW,
                        List.of(new ScoredReason("A", "t", 80, "d"))));
        TestKit.checkThrows("reason sum below score rejected", IllegalArgumentException.class,
                () -> new CustomerRiskScore(1, "n", 50, RiskBand.HIGH,
                        List.of(new ScoredReason("A", "t", 30, "d"))));
        TestKit.checkThrows("group reason sum below score rejected", IllegalArgumentException.class,
                () -> new GroupRiskScore(0, List.of(1L), 50, RiskBand.HIGH,
                        List.of(new ScoredReason("A", "t", 10, "d"))));
        // the audit form stays ASCII even when reasons are Persian
        checkEquals("audit form is ASCII code+points", "MONEY_CYCLE(+35)",
                new ScoredReason("MONEY_CYCLE", "حلقه", 35, "شرح").auditForm());
        // ranking is score desc then id asc
        List<CustomerRiskScore> ranked = List.of(
                new CustomerRiskScore(2, "b", 10, RiskBand.LOW,
                        List.of(new ScoredReason("B", "t", 10, "d"))),
                new CustomerRiskScore(1, "a", 80, RiskBand.CRITICAL,
                        List.of(new ScoredReason("A", "t", 80, "d"))));
        RiskAssessment assessment = new RiskAssessment(ranked, List.of());
        checkEquals("ranked by score desc",
                List.of(1L, 2L), assessment.customers().stream().map(CustomerRiskScore::customerId).toList());
        checkEquals("tied scores break by id asc",
                List.of(1L, 2L), new RiskAssessment(List.of(
                        new CustomerRiskScore(2, "b", 10, RiskBand.LOW, List.of(new ScoredReason("B", "t", 10, "d"))),
                        new CustomerRiskScore(1, "a", 10, RiskBand.LOW, List.of(new ScoredReason("A", "t", 10, "d")))),
                        List.of()).customers().stream().map(CustomerRiskScore::customerId).toList());
    }
}
