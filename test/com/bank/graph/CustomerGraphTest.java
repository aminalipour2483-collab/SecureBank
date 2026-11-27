package com.bank.graph;

import com.bank.exception.AccessDeniedException;
import com.bank.exception.BankException;
import com.bank.graph.application.CustomerGraphRepository;
import com.bank.graph.application.CustomerGraphService;
import com.bank.graph.application.CustomerGraphServiceImpl;
import com.bank.graph.domain.*;
import com.bank.security.AdminPrincipal;
import com.bank.security.ConfiguredAdminAuthenticationService;
import com.bank.util.BCryptUtil;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.LongStream;

import static com.bank.testkit.TestKit.*;

/** Domain + application tests for the customer graph. Needs no database. */
public class CustomerGraphTest {

    static final LocalDateTime T0 = LocalDateTime.of(2026, 1, 1, 12, 0);
    static final GraphCriteria ALL = new GraphCriteria(null, true, 100);

    static CustomerIdentity c(long id) { return new CustomerIdentity(id, "Customer " + id, false); }
    static CustomerIdentity banned(long id) { return new CustomerIdentity(id, "Customer " + id, true); }
    static TransferFlow flow(CustomerIdentity a, CustomerIdentity b, long n, String total, LocalDateTime last) {
        return new TransferFlow(a, b, n, new BigDecimal(total), last);
    }
    static GraphSourceData flows(TransferFlow... f) {
        return new GraphSourceData(List.of(f), false, List.of(), false);
    }
    static SharedPhoneMember phone(String p, CustomerIdentity who) { return new SharedPhoneMember(p, who); }

    public static void main(String[] args) {
        builderRules();
        sharedPhoneRules();
        criteriaRules();
        componentRules();
        serviceAuthorization();
        finish("CustomerGraphTest");
    }

    // ------------------------------------------------------------------------------------------
    static void builderRules() {
        section("[1] Builder: transfer relationships");
        CustomerGraph g = CustomerGraphBuilder.build(flows(
                flow(c(1), c(2), 3, "300.00", T0),
                flow(c(2), c(1), 1, "50.00", T0.plusDays(1)),
                flow(c(2), c(3), 2, "75.50", T0.plusDays(2))), ALL, T0);
        checkEquals("3 customers", 3, g.nodes().size());
        checkEquals("3 directed edges (A→B and B→A are separate)", 3, g.edges().size());
        checkEquals("total transfers counted", 6L, g.totalTransfers());
        CustomerNode n2 = g.node(2).orElseThrow();
        checkEquals("customer 2 sent 3 (1+2)", 3L, n2.transfersSent());
        checkEquals("customer 2 received 3", 3L, n2.transfersReceived());
        checkEquals("customer 2 has 2 distinct connections", 2, n2.connections());
        RelationshipEdge e12 = g.edges().stream().filter(e -> e.fromId() == 1 && e.toId() == 2).findFirst().orElseThrow();
        checkEquals("edge 1→2 amount", new BigDecimal("300.00"), e12.totalAmount());
        check("transfer edges are directed", e12.type().isDirected());

        CustomerGraph merged = CustomerGraphBuilder.build(flows(
                flow(c(1), c(2), 2, "10.00", T0),
                flow(c(1), c(2), 3, "20.00", T0.plusHours(5))), ALL, T0);
        checkEquals("duplicate pair rows merge into one edge", 1, merged.edges().size());
        checkEquals("merged count", 5L, merged.edges().get(0).transferCount());
        checkEquals("merged amount", new BigDecimal("30.00"), merged.edges().get(0).totalAmount());
        checkEquals("merged last activity is the latest", T0.plusHours(5), merged.edges().get(0).lastActivity());

        CustomerGraph self = CustomerGraphBuilder.build(flows(flow(c(1), c(1), 4, "5.00", T0)), ALL, T0);
        check("self-transfer is not a relationship", self.isEmpty() && self.edges().isEmpty());

        CustomerGraph zero = CustomerGraphBuilder.build(flows(flow(c(1), c(2), 0, "0", T0)), ALL, T0);
        check("zero-count flow ignored", zero.isEmpty());

        CustomerGraph ban = CustomerGraphBuilder.build(flows(flow(banned(1), c(2), 1, "9.99", T0)), ALL, T0);
        checkEquals("banned customers counted", 1L, ban.bannedCount());
        check("banned flag preserved on node", ban.node(1).orElseThrow().banned());

        CustomerGraph empty = CustomerGraphBuilder.build(GraphSourceData.empty(), ALL, T0);
        check("empty source → empty graph", empty.isEmpty() && !empty.isTruncated());

        GraphSourceData cut = new GraphSourceData(List.of(flow(c(1), c(2), 1, "1", T0)), true, List.of(), false);
        check("flow truncation is surfaced", CustomerGraphBuilder.build(cut, ALL, T0).isTruncated());
        check("graph is immutable (nodes)", throwsUnsupported(() -> g.nodes().add(null)));
        check("graph is immutable (edges)", throwsUnsupported(() -> g.edges().clear()));
    }

    static boolean throwsUnsupported(Runnable r) {
        try { r.run(); return false; } catch (UnsupportedOperationException e) { return true; }
    }

    // ------------------------------------------------------------------------------------------
    static void sharedPhoneRules() {
        section("[2] Builder: shared phone relationships");
        GraphSourceData src = new GraphSourceData(List.of(), false, List.of(
                phone("+12025550101", c(1)), phone("+12025550101", c(2)), phone("+12025550101", c(3)),
                phone("+12025559999", c(4)), phone("+12025559999", c(5))), false);
        CustomerGraph g = CustomerGraphBuilder.build(src, ALL, T0);
        checkEquals("group of 3 → 3 pairwise edges, group of 2 → 1 edge", 4, g.edges().size());
        check("phone edges are undirected", g.edges().stream().noneMatch(e -> e.type().isDirected()));
        check("undirected edges normalised from<to", g.edges().stream().allMatch(e -> e.fromId() < e.toId()));
        check("evidence masks the number to last 4 digits",
                g.edges().stream().allMatch(e -> e.evidence().endsWith("0101") || e.evidence().endsWith("9999"))
                        && g.edges().stream().noneMatch(e -> e.evidence().contains("+1202")));
        checkEquals("phone-only customers have no transfer counts", 0L, g.node(1).orElseThrow().transfersSent());

        CustomerGraph off = CustomerGraphBuilder.build(src, new GraphCriteria(null, false, 100), T0);
        check("includeSharedPhone=false ignores phone data", off.isEmpty());

        List<SharedPhoneMember> big = new ArrayList<>();
        for (long i = 1; i <= CustomerGraphBuilder.MAX_SHARED_GROUP_SIZE + 1; i++) big.add(phone("000", c(i)));
        big.add(phone("111", c(100))); big.add(phone("111", c(101)));
        CustomerGraph capped = CustomerGraphBuilder.build(new GraphSourceData(List.of(), false, big, false), ALL, T0);
        checkEquals("oversized group omitted and counted", 1, capped.omittedSharedGroups());
        checkEquals("only the small group remains", 1, capped.edges().size());

        List<SharedPhoneMember> cutRows = List.of(phone("111", c(1)), phone("111", c(2)), phone("222", c(3)));
        CustomerGraph part = CustomerGraphBuilder.build(new GraphSourceData(List.of(), false, cutRows, true), ALL, T0);
        check("last group dropped when rows were truncated (may be incomplete)",
                part.edges().size() == 1 && part.isTruncated() && part.node(3).isEmpty());

        List<SharedPhoneMember> dup = List.of(phone("111", c(1)), phone("111", c(1)));
        check("same customer twice in a group is not a link",
                CustomerGraphBuilder.build(new GraphSourceData(List.of(), false, dup, false), ALL, T0).isEmpty());

        // A pair that both transferred money AND shares a phone keeps two distinct edges.
        CustomerGraph both = CustomerGraphBuilder.build(new GraphSourceData(
                List.of(flow(c(1), c(2), 1, "5", T0)), false,
                List.of(phone("111", c(1)), phone("111", c(2))), false), ALL, T0);
        checkEquals("transfer + phone link coexist", 2, both.edges().size());
        checkEquals("…but the pair counts as ONE connection per customer", 1, both.node(1).orElseThrow().connections());

        GraphCriteria tiny = new GraphCriteria(null, true, 2);
        List<SharedPhoneMember> lots = new ArrayList<>();
        for (long i = 1; i <= 5; i++) lots.add(phone("777", c(i)));      // 10 pairs
        CustomerGraph limited = CustomerGraphBuilder.build(new GraphSourceData(List.of(), false, lots, false), tiny, T0);
        check("phone edges capped at maxEdges and flagged", limited.edges().size() == 2 && limited.isTruncated());
        check("nodes with no remaining edge are dropped",
                limited.nodes().stream().allMatch(n -> n.connections() > 0));
    }

    // ------------------------------------------------------------------------------------------
    static void criteriaRules() {
        section("[3] GraphCriteria validation");
        checkThrows("maxEdges 0 rejected", IllegalArgumentException.class, () -> new GraphCriteria(null, true, 0));
        checkThrows("maxEdges above limit rejected", IllegalArgumentException.class,
                () -> new GraphCriteria(null, true, GraphCriteria.MAX_EDGES_LIMIT + 1));
        checkThrows("lookback 0 rejected", IllegalArgumentException.class, () -> new GraphCriteria(0, true, 10));
        checkThrows("negative lookback rejected", IllegalArgumentException.class, () -> new GraphCriteria(-5, true, 10));
        check("valid criteria accepted", new GraphCriteria(30, false, 10).lookbackDays() == 30);
        checkEquals("defaults describe all time", "all time", GraphCriteria.defaults().describePeriod());
    }

    // ------------------------------------------------------------------------------------------
    static void componentRules() {
        section("[4] Connected components");
        CustomerGraph g = CustomerGraphBuilder.build(flows(
                flow(c(1), c(2), 1, "1", T0), flow(c(3), c(2), 1, "1", T0),      // {1,2,3}
                flow(c(10), c(11), 1, "1", T0),                                  // {10,11}
                flow(c(20), c(21), 1, "1", T0), flow(c(22), c(21), 1, "1", T0), flow(c(22), c(23), 1, "1", T0) // {20..23}
        ), ALL, T0);
        List<List<Long>> comps = g.connectedComponents();
        checkEquals("3 components", 3, comps.size());
        checkEquals("largest first", List.of(20L, 21L, 22L, 23L), comps.get(0));
        checkEquals("then by size", List.of(1L, 2L, 3L), comps.get(1));
        checkEquals("direction ignored when grouping", List.of(10L, 11L), comps.get(2));
        checkEquals("edgesInvolving selection", 2, g.edgesInvolving(Set.of(2L)).size());
    }

    // ------------------------------------------------------------------------------------------
    static class RecordingRepository implements CustomerGraphRepository {
        int loadCalls, detailCalls;
        Set<Long> lastIds; int lastLimit;
        GraphSourceData data = flows(flow(c(1), c(2), 1, "10", T0));

        @Override public GraphSourceData loadGraphSource(GraphCriteria criteria) { loadCalls++; return data; }
        @Override public List<TransferRecord> findTransfersInvolving(Set<Long> ids, int limit) {
            detailCalls++; lastIds = ids; lastLimit = limit;
            return List.of(new TransferRecord(1, c(1), c(2), BigDecimal.TEN, "REF", "COMPLETED", T0));
        }
    }

    static void serviceAuthorization() {
        section("[5] Service: administrator-only access and limits");
        RecordingRepository repo = new RecordingRepository();
        Clock clock = Clock.fixed(Instant.parse("2026-06-01T10:00:00Z"), ZoneOffset.UTC);
        CustomerGraphService service = new CustomerGraphServiceImpl(repo, clock);

        checkThrows("generateGraph without admin → AccessDenied", AccessDeniedException.class,
                () -> service.generateGraph(null, ALL));
        checkThrows("getTransfersInvolving without admin → AccessDenied", AccessDeniedException.class,
                () -> service.getTransfersInvolving(null, Set.of(1L)));
        checkEquals("repository never touched on denied calls", 0, repo.loadCalls + repo.detailCalls);

        AdminPrincipal admin = new ConfiguredAdminAuthenticationService("root", BCryptUtil.hash("pw-123"), clock)
                .authenticate("root", "pw-123").orElseThrow();

        CustomerGraph g = service.generateGraph(admin, ALL);
        checkEquals("admin gets the graph", 2, g.nodes().size());
        checkEquals("graph timestamp comes from the injected clock", LocalDateTime.of(2026, 6, 1, 10, 0), g.generatedAt());
        checkEquals("repository called once", 1, repo.loadCalls);
        checkThrows("null criteria rejected", NullPointerException.class, () -> service.generateGraph(admin, null));

        check("empty selection → empty result, no query",
                service.getTransfersInvolving(admin, Set.of()).isEmpty() && repo.detailCalls == 0);
        List<TransferRecord> rows = service.getTransfersInvolving(admin, Set.of(1L, 2L));
        checkEquals("detail rows returned", 1, rows.size());
        checkEquals("detail limit enforced by service", CustomerGraphService.MAX_DETAIL_ROWS, repo.lastLimit);
        Set<Long> tooMany = new HashSet<>();
        LongStream.rangeClosed(1, CustomerGraphService.MAX_DETAIL_CUSTOMERS + 1).forEach(tooMany::add);
        checkThrows("too many customers rejected before querying", BankException.class,
                () -> service.getTransfersInvolving(admin, tooMany));
        checkEquals("…and no extra query was made", 1, repo.detailCalls);
    }
}
