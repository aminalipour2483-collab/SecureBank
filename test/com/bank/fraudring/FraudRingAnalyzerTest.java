package com.bank.fraudring;

import com.bank.fraudring.domain.CentralityMeasures;
import com.bank.fraudring.domain.Community;
import com.bank.fraudring.domain.CustomerRiskFact;
import com.bank.fraudring.domain.FraudRingAnalyzer;
import com.bank.fraudring.domain.FraudRingCriteria;
import com.bank.fraudring.domain.FraudRingFlow;
import com.bank.fraudring.domain.FraudRingGraph;
import com.bank.fraudring.domain.FraudRingScope;
import com.bank.fraudring.domain.MoneyCycle;
import com.bank.graph.domain.CustomerIdentity;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.bank.testkit.TestKit;

/**
 * Domain-engine tests on hand-built graphs: the same style as CustomerGraphTest —
 * pure domain, no JDBC, no Swing. Every fixture is small enough to verify by hand.
 */
public final class FraudRingAnalyzerTest {

    public static void main(String[] args) {
        TestKit.section("[1] empty and single-flow graphs");
        emptyGraphBehaves();
        singleFlowIsASimplePair();

        TestKit.section("[2] cycle detection");
        twoNodeCycleIsFound();
        threeNodeCycleIsFound();
        noCycleWhenDirectionBreaks();
        cycleAmountsAreTheRingTotals();
        cyclesReportedOnceCanonically();
        deepCyclesAreCappedAtMaxHops();

        TestKit.section("[3] community detection");
        cliqueSplitsFromOutsider();
        twoCliquesBecomeTwoCommunities();
        sharedPhoneLinksTwoCustomers();
        communitiesAreSortedBySizeThenId();
        communityDensityIsComputed();
        communityInternalVolumeIsComputed();
        denseCycleEnumerationReportsItsCap();

        TestKit.section("[4] centrality");
        hubHasHighestDegree();
        courierHasHighestBetweenness();
        pageRankFavoursMoneyDestinations();
        isolatedNodeHasZeroCentrality();

        TestKit.section("[5] determinism");
        repeatedRunsAreIdentical();
        flowOrderDoesNotChangeOutput();

        TestKit.section("[6] scope filtering");
        subsetScopeKeepsOnlyChosenIds();
        singleScopeKeepsOnlyThatCustomer();

        TestKit.finish("FraudRingAnalyzerTest");
    }

    // ─────────────────────────── fixtures ───────────────────────────

    private static CustomerIdentity id(long n) {
        return CustomerIdentity.of(n, "C" + n, "Test", false);
    }

    private static FraudRingFlow flow(long from, long to, long count, String amount) {
        return new FraudRingFlow(id(from), id(to), true, count,
                new BigDecimal(amount), LocalDateTime.of(2026, 1, 15, 10, 0));
    }

    private static FraudRingFlow flowIn(long to, long from, long count, String amount) {
        return new FraudRingFlow(id(to), id(from), false, count,
                new BigDecimal(amount), LocalDateTime.of(2026, 1, 15, 10, 0));
    }

    private static Map<Long, CustomerRiskFact> noFacts(List<FraudRingFlow> flows) {
        Map<Long, CustomerRiskFact> facts = new LinkedHashMap<>();
        for (FraudRingFlow f : flows) {
            facts.putIfAbsent(f.customer().id(), CustomerRiskFact.unknown(f.customer().id()));
            facts.putIfAbsent(f.counterpart().id(), CustomerRiskFact.unknown(f.counterpart().id()));
        }
        return facts;
    }

    private static FraudRingGraph run(List<FraudRingFlow> flows) {
        return FraudRingAnalyzer.analyze(flows, Map.of(), noFacts(flows), FraudRingCriteria.full());
    }

    // ─────────────────────────── [1] basics ───────────────────────────

    static void emptyGraphBehaves() {
        FraudRingGraph g = run(List.of());
        TestKit.check("no nodes", g.isEmpty());
        TestKit.check("no communities", g.communities().isEmpty());
        TestKit.check("no cycles", g.cycles().isEmpty());
        TestKit.checkEquals("zero edges", 0, g.edgeCount());
    }

    static void singleFlowIsASimplePair() {
        FraudRingGraph g = run(List.of(flow(1, 2, 3, "900.00")));
        TestKit.checkEquals("two nodes", 2, g.nodeCount());
        TestKit.checkEquals("one community of both", 1, g.communities().size());
        // one directed edge is NOT a cycle: a ring A→B→A needs the B→A leg too
        TestKit.checkEquals("one-way flow is no cycle", 0L, g.cycleCount());
        TestKit.checkEquals("degree still counts the single counterparty", 1,
                g.centralityOf(1).degree());
    }

    // ─────────────────────────── [2] cycles ───────────────────────────

    static void twoNodeCycleIsFound() {
        // 1→2 and 2→1: two directed flows, one ring
        FraudRingGraph g = run(List.of(flow(1, 2, 2, "500.00"), flow(2, 1, 1, "300.00")));
        TestKit.check("2-node ring found", g.cycleCount() == 1);
        TestKit.checkEquals("ring order", List.of(1L, 2L), g.cycles().get(0).memberIds());
        TestKit.checkEquals("min edge is the weaker leg", new BigDecimal("300.00"),
                g.cycles().get(0).minEdgeAmount());
        TestKit.checkEquals("ring total both legs", new BigDecimal("800.00"),
                g.cycles().get(0).totalAmount());
    }

    static void threeNodeCycleIsFound() {
        // A(1)→B(2)→C(3)→A(1): the classic laundering ring
        FraudRingGraph g = run(List.of(
                flow(1, 2, 1, "400.00"),
                flow(2, 3, 1, "350.00"),
                flow(3, 1, 1, "300.00")));
        List<MoneyCycle> cycles = g.cycles();
        TestKit.check("3-node ring found", cycles.size() == 1);
        TestKit.checkEquals("ring order starts at the smallest id",
                List.of(1L, 2L, 3L), cycles.get(0).memberIds());
        TestKit.checkEquals("3 hops", 3, cycles.get(0).hopCount());
        TestKit.checkEquals("ring total", new BigDecimal("1050.00"), cycles.get(0).totalAmount());
    }

    static void noCycleWhenDirectionBreaks() {
        // 1→2→3→2: money returns to 2, never to 1 — no ring through 1, but the
        // B→C→B leg IS a legitimate 2-cycle and must be reported
        FraudRingGraph g = run(List.of(
                flow(1, 2, 1, "100.00"),
                flow(2, 3, 1, "100.00"),
                flow(3, 2, 1, "100.00")));
        TestKit.checkEquals("only the 2-3 back-and-forth is a ring", 1L, g.cycleCount());
        TestKit.checkEquals("the ring is 2→3→2", List.of(2L, 3L), g.cycles().get(0).memberIds());
        TestKit.check("no cycle claims customer 1", !g.cycles().get(0).involves(1));
    }

    static void cycleAmountsAreTheRingTotals() {
        // ring 1→2→3→1 plus a side leg 1→4
        FraudRingGraph g = run(List.of(
                flow(1, 2, 1, "400.00"),
                flow(2, 3, 1, "350.00"),
                flow(3, 1, 1, "300.00"),
                flow(1, 4, 5, "999.00")));
        TestKit.check("still exactly one ring", g.cycleCount() == 1);
        TestKit.checkEquals("side leg excluded from ring total",
                new BigDecimal("1050.00"), g.cycles().get(0).totalAmount());
    }

    static void cyclesReportedOnceCanonically() {
        // symmetric triangle: every node can start the walk; canonical form keeps ONE row
        FraudRingGraph g = run(List.of(
                flow(1, 2, 1, "100.00"),
                flow(2, 3, 1, "100.00"),
                flow(3, 1, 1, "100.00")));
        TestKit.checkEquals("triangle = one canonical cycle", 1L, g.cycleCount());
        TestKit.checkEquals("canonical start = smallest id", 1L,
                g.cycles().get(0).memberIds().get(0).longValue());
    }

    static void deepCyclesAreCappedAtMaxHops() {
        // a 12-node directed ring: only the bounded walk length 2..8 exists, so the
        // analyzer reports the sub-rings of the big circle, never an unbounded DFS
        List<FraudRingFlow> ringFlows = new ArrayList<>();
        for (long i = 1; i <= 12; i++) ringFlows.add(flow(i, i % 12 + 1, 1, "50.00"));
        FraudRingGraph g = run(ringFlows);
        TestKit.check("no cycle longer than MAX_HOPS",
                g.cycles().stream().allMatch(c -> c.hopCount() <= MoneyCycle.MAX_HOPS));
        TestKit.check("the 12-cycle itself is not reported",
                g.cycles().stream().noneMatch(c -> c.hopCount() == 12));
    }

    // ─────────────────────────── [3] communities ───────────────────────────

    static void cliqueSplitsFromOutsider() {
        // tight triangle 1-2-3 (weight 5 per direction), one weak link 3-4 (weight 1),
        // isolated pair 5-6 (weight 2). Label propagation is weight-driven, BUT a
        // leaf always follows its only neighbour: 4 merges into the triangle's
        // community while the disconnected pair 5-6 stays its own group.
        FraudRingGraph g = run(List.of(
                flow(1, 2, 5, "500.00"), flow(2, 1, 5, "500.00"),
                flow(2, 3, 5, "500.00"), flow(3, 2, 5, "500.00"),
                flow(1, 3, 5, "500.00"), flow(3, 1, 5, "500.00"),
                flow(3, 4, 1, "10.00"),
                flow(5, 6, 2, "200.00"), flow(6, 5, 2, "200.00")));
        List<Community> communities = g.communities();
        TestKit.checkEquals("two communities: triangle+leaf, and the pair", 2,
                communities.size());
        TestKit.checkEquals("largest first: the triangle with its leaf",
                List.of(1L, 2L, 3L, 4L), communities.get(0).memberIds());
        TestKit.checkEquals("the disconnected pair is its own community",
                List.of(5L, 6L), communities.get(1).memberIds());
        // density reflects the weak tie: 4 of 6 possible edges are internal
        TestKit.checkEquals("triangle+leaf density is 0.6667", 0.6667,
                communities.get(0).density());
        TestKit.checkEquals("pair density is 1.0", 1.0, communities.get(1).density());
    }

    static void twoCliquesBecomeTwoCommunities() {
        // two disjoint dense groups, no link between them
        List<FraudRingFlow> flows = new ArrayList<>();
        for (long a = 1; a <= 4; a++) {
            for (long b = 1; b <= 4; b++) {
                if (a != b) flows.add(flow(a, b, 3, "300.00"));
            }
        }
        for (long a = 11; a <= 14; a++) {
            for (long b = 11; b <= 14; b++) {
                if (a != b) flows.add(flow(a, b, 3, "300.00"));
            }
        }
        FraudRingGraph g = run(flows);
        TestKit.checkEquals("two cliques = two communities", 2, g.communities().size());
        TestKit.checkEquals("both sized 4", List.of(4, 4),
                g.communities().stream().map(Community::size).toList());
        TestKit.check("clique one fully together",
                g.communities().get(0).memberIds().containsAll(List.of(1L, 2L, 3L, 4L)));
    }

    static void sharedPhoneLinksTwoCustomers() {
        // no transfers at all, but 7 and 8 share a phone → one community of two
        Map<String, List<CustomerIdentity>> phones = Map.of("+15550007",
                List.of(id(7), id(8)));
        FraudRingGraph g = FraudRingAnalyzer.analyze(List.of(), phones,
                Map.of(id(7).id(), CustomerRiskFact.unknown(7), id(8).id(), CustomerRiskFact.unknown(8)),
                FraudRingCriteria.full());
        TestKit.checkEquals("phone-only link makes one community", 1, g.communities().size());
        TestKit.checkEquals("the two phone sharers",
                List.of(7L, 8L), g.communities().get(0).memberIds());
        TestKit.check("phone recorded on the community",
                g.communities().get(0).sharedPhones().contains("+15550007"));
    }

    static void communitiesAreSortedBySizeThenId() {
        // pair (1,2) big-weight, triangle (3,4,5), pair (6,7)
        FraudRingGraph g = run(List.of(
                flow(1, 2, 9, "900.00"), flow(2, 1, 9, "900.00"),
                flow(3, 4, 2, "80.00"), flow(4, 3, 2, "80.00"),
                flow(4, 5, 2, "80.00"), flow(5, 4, 2, "80.00"),
                flow(6, 7, 1, "10.00"), flow(7, 6, 1, "10.00")));
        List<Integer> sizes = g.communities().stream().map(Community::size).toList();
        TestKit.check("sorted by size descending", isDescending(sizes));
        TestKit.checkEquals("community ids are 0,1,2 in that order", List.of(0, 1, 2),
                g.communities().stream().map(c -> (int) c.communityId()).toList());
    }

    static void communityDensityIsComputed() {
        // triangle: 3 undirected edges of 3 possible → density 1.0
        FraudRingGraph g = run(List.of(
                flow(1, 2, 1, "10.00"), flow(2, 1, 1, "10.00"),
                flow(2, 3, 1, "10.00"), flow(3, 2, 1, "10.00"),
                flow(1, 3, 1, "10.00"), flow(3, 1, 1, "10.00")));
        TestKit.checkEquals("clique density = 1.0", 1.0, g.communities().get(0).density());
        // path 1-2-3: 2 edges of 3 possible → 2*(2)/6 = 0.6667
        FraudRingGraph path = run(List.of(
                flow(1, 2, 1, "10.00"), flow(2, 3, 1, "10.00")));
        TestKit.checkEquals("path density = 0.6667", 0.6667,
                path.communities().get(0).density());
    }


    static void communityInternalVolumeIsComputed() {
        FraudRingGraph g = run(List.of(
                flow(1, 2, 1, "100.00"),
                flow(2, 1, 1, "50.00"),
                flow(2, 3, 1, "25.00"),
                flow(3, 1, 1, "75.00")));
        Community community = g.communities().get(0);
        TestKit.checkEquals("community stores its directed internal transfer volume",
                new BigDecimal("250.00"), community.internalVolume());
    }

    static void denseCycleEnumerationReportsItsCap() {
        List<FraudRingFlow> dense = new ArrayList<>();
        for (long from = 1; from <= 10; from++) {
            for (long to = 1; to <= 10; to++) {
                if (from != to) dense.add(flow(from, to, 1, "1.00"));
            }
        }
        FraudRingGraph graph = run(dense);
        TestKit.check("cycle output is bounded", graph.cycles().size() <= 5_000);
        TestKit.check("dense graph reports non-exhaustive cycle enumeration",
                graph.cycleDetectionTruncated());
    }

    // ─────────────────────────── [4] centrality ───────────────────────────

    static void hubHasHighestDegree() {
        // star: 1 talks to 2,3,4; the others only to 1
        FraudRingGraph g = run(List.of(
                flow(1, 2, 1, "10.00"), flow(1, 3, 1, "10.00"), flow(1, 4, 1, "10.00"),
                flow(2, 1, 1, "10.00"), flow(3, 1, 1, "10.00"), flow(4, 1, 1, "10.00")));
        CentralityMeasures hub = g.centralityOf(1);
        TestKit.checkEquals("hub degree 3", 3, hub.degree());
        TestKit.check("hub strictly highest degree",
                g.nodeViews().stream().allMatch(v -> v.centrality().degree() <= hub.degree()));
    }

    static void courierHasHighestBetweenness() {
        // two cliques joined only through courier 9:  1-2, 3-4 cliques; 9 bridges
        List<FraudRingFlow> flows = new ArrayList<>(List.of(
                flow(1, 2, 3, "30.00"), flow(2, 1, 3, "30.00"),
                flow(3, 4, 3, "30.00"), flow(4, 3, 3, "30.00"),
                flow(1, 9, 1, "10.00"), flow(9, 3, 1, "10.00")));
        FraudRingGraph g = run(flows);
        double courier = g.centralityOf(9).betweenness();
        TestKit.check("courier betweenness > 0", courier > 0);
        TestKit.check("courier has the highest betweenness",
                g.nodeViews().stream().noneMatch(v -> v.customerId() != 9
                        && v.centrality().betweenness() > courier));
        TestKit.checkEquals("clique members sit at 0", 0.0, g.centralityOf(2).betweenness());
    }

    static void pageRankFavoursMoneyDestinations() {
        // three hubs all pay the collector 5 → collector's PageRank leads
        FraudRingGraph g = run(List.of(
                flow(1, 5, 1, "10.00"), flow(2, 5, 1, "10.00"), flow(3, 5, 1, "10.00"),
                flow(5, 1, 1, "10.00")));
        double collector = g.centralityOf(5).pageRank();
        TestKit.check("collector PageRank above average source",
                collector > g.centralityOf(2).pageRank());
        TestKit.check("pageRank values are finite and non-negative",
                g.nodeViews().stream().allMatch(v -> v.centrality().pageRank() >= 0
                        && Double.isFinite(v.centrality().pageRank())));
        double total = g.centrality().values().stream().mapToDouble(CentralityMeasures::pageRank).sum();
        TestKit.check("weighted PageRank redistributes dangling mass and sums to one",
                Math.abs(total - 1d) < 0.00001d, "sum=" + total);
    }

    static void isolatedNodeHasZeroCentrality() {
        // 5 stands alone (a customer with a phone group but no transfers)
        FraudRingGraph g = FraudRingAnalyzer.analyze(List.of(flow(1, 2, 1, "10.00")),
                Map.of("+15550009", List.of(id(5), id(6))),
                Map.of(5L, CustomerRiskFact.unknown(5), 6L, CustomerRiskFact.unknown(6)),
                FraudRingCriteria.full());
        CentralityMeasures lone = g.centralityOf(5);
        TestKit.checkEquals("phone-only node has zero transfer degree", 0, lone.degree());
        TestKit.checkEquals("phone-only node has zero betweenness", 0.0, lone.betweenness());
    }

    // ─────────────────────────── [5] determinism ───────────────────────────

    static void repeatedRunsAreIdentical() {
        // a graph with cycles, cliques and a courier — 40 checks on 2 runs
        List<FraudRingFlow> flows = new ArrayList<>(List.of(
                flow(1, 2, 5, "500.00"), flow(2, 1, 5, "500.00"),
                flow(2, 3, 2, "80.00"), flow(3, 2, 2, "80.00"),
                flow(3, 1, 1, "60.00"),
                flow(3, 9, 1, "40.00"), flow(9, 4, 1, "40.00"), flow(4, 5, 3, "90.00"),
                flow(5, 4, 3, "90.00"), flow(4, 6, 1, "20.00"), flow(6, 5, 1, "20.00"),
                flow(5, 7, 1, "15.00"), flow(7, 8, 1, "15.00"), flow(8, 5, 1, "15.00")));
        FraudRingGraph a = run(flows);
        FraudRingGraph b = run(flows);
        TestKit.checkEquals("node sets identical", a.sortedNodeIds(), b.sortedNodeIds());
        TestKit.checkEquals("community partition identical",
                a.communities().stream().map(Community::memberIds).toList(),
                b.communities().stream().map(Community::memberIds).toList());
        TestKit.checkEquals("community densities identical",
                a.communities().stream().map(Community::density).toList(),
                b.communities().stream().map(Community::density).toList());
        TestKit.checkEquals("cycle list identical",
                a.cycles().stream().map(MoneyCycle::memberIds).toList(),
                b.cycles().stream().map(MoneyCycle::memberIds).toList());
        TestKit.checkEquals("cycle totals identical",
                a.cycles().stream().map(MoneyCycle::totalAmount).toList(),
                b.cycles().stream().map(MoneyCycle::totalAmount).toList());
        TestKit.checkEquals("betweenness identical",
                a.nodeViews().stream().map(v -> v.centrality().betweenness()).toList(),
                b.nodeViews().stream().map(v -> v.centrality().betweenness()).toList());
        TestKit.checkEquals("pageRank identical",
                a.nodeViews().stream().map(v -> v.centrality().pageRank()).toList(),
                b.nodeViews().stream().map(v -> v.centrality().pageRank()).toList());
    }

    static void flowOrderDoesNotChangeOutput() {
        // same edges, reversed/shuffled insertion order → same analysis
        List<FraudRingFlow> ordered = List.of(
                flow(1, 2, 3, "300.00"), flow(2, 3, 2, "80.00"),
                flow(3, 1, 1, "60.00"), flow(4, 1, 1, "20.00"));
        List<FraudRingFlow> shuffled = List.of(
                flow(4, 1, 1, "20.00"), flow(3, 1, 1, "60.00"),
                flow(2, 3, 2, "80.00"), flow(1, 2, 3, "300.00"));
        FraudRingGraph a = run(ordered);
        FraudRingGraph b = run(shuffled);
        TestKit.checkEquals("communities unaffected by row order",
                a.communities().stream().map(Community::memberIds).toList(),
                b.communities().stream().map(Community::memberIds).toList());
        TestKit.checkEquals("cycles unaffected by row order",
                a.cycles().stream().map(MoneyCycle::memberIds).toList(),
                b.cycles().stream().map(MoneyCycle::memberIds).toList());
    }

    // ─────────────────────────── [6] scope ───────────────────────────

    static void subsetScopeKeepsOnlyChosenIds() {
        // ring 1→2→3→1 plus ring 4→5→6→4; SUBSET keeps only the first ring
        List<FraudRingFlow> flows = List.of(
                flow(1, 2, 1, "100.00"), flow(2, 3, 1, "100.00"), flow(3, 1, 1, "100.00"),
                flow(4, 5, 1, "100.00"), flow(5, 6, 1, "100.00"), flow(6, 4, 1, "100.00"));
        FraudRingGraph g = FraudRingAnalyzer.analyze(flows, Map.of(), noFacts(flows),
                FraudRingCriteria.subset(Set.of(1L, 2L, 3L)));
        TestKit.checkEquals("only the chosen trio", List.of(1L, 2L, 3L), g.sortedNodeIds());
        TestKit.checkEquals("only the first ring", 1L, g.cycleCount());
    }

    static void singleScopeKeepsOnlyThatCustomer() {
        // SINGLE = one-hop ego network: customer 2's flows and direct counterparties
        List<FraudRingFlow> flows = List.of(
                flow(1, 2, 1, "100.00"), flow(2, 3, 1, "100.00"), flow(4, 5, 1, "100.00"));
        FraudRingGraph g = FraudRingAnalyzer.analyze(flows, Map.of(), noFacts(flows),
                FraudRingCriteria.single(2));
        TestKit.checkEquals("ego network keeps the customer and its counterparties",
                List.of(1L, 2L, 3L), g.sortedNodeIds());
        TestKit.checkEquals("the untouched pair 4-5 is out", 3, g.nodeCount());
        // ego of a customer with no flows: the chosen customer is present but alone
        FraudRingGraph lone = FraudRingAnalyzer.analyze(List.of(flow(4, 5, 1, "10.00")),
                Map.of(), Map.of(), FraudRingCriteria.single(9));
        TestKit.checkEquals("customer with no flows yields a lone-node graph",
                List.of(9L), lone.sortedNodeIds());
        TestKit.checkEquals("no relationships to walk", 0L, lone.cycleCount());
    }

    // ─────────────────────────── helpers ───────────────────────────

    private static boolean isDescending(List<Integer> sizes) {
        for (int i = 1; i < sizes.size(); i++) {
            if (sizes.get(i - 1) < sizes.get(i)) return false;
        }
        return true;
    }
}
