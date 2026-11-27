package com.bank.fraudring.domain;

import com.bank.graph.domain.CustomerIdentity;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Immutable result of one {@link FraudRingAnalyzer} run: the structural half of
 * the fraud-ring analysis. The risk-scoring layer (Phase 3) consumes this and
 * adds behavioural scores; the JSON columns of {@code fraud_ring_analyses}
 * serialise it.
 *
 * <p>Carries everything the admin UI needs to draw the graph overlay:
 * nodes, directed flows, communities, cycles, centrality — all deterministic
 * functions of the snapshot.
 */
public final class FraudRingGraph {

    private final List<FraudRingFlow> flows;
    private final Set<Long> nodeIds;                       // sorted
    private final List<Community> communities;
    private final List<MoneyCycle> cycles;
    private final Map<Long, CentralityMeasures> centrality;
    private final Map<Long, CustomerIdentity> identities;
    private final Map<Long, CustomerRiskFact> facts;
    private final FraudRingCriteria criteria;
    private final boolean cycleDetectionTruncated;

    FraudRingGraph(List<FraudRingFlow> flows,
                   Set<Long> nodeIds,
                   List<Community> communities,
                   List<MoneyCycle> cycles,
                   Map<Long, CentralityMeasures> centrality,
                   Map<Long, CustomerIdentity> identities,
                   Map<Long, CustomerRiskFact> facts,
                   FraudRingCriteria criteria,
                   boolean cycleDetectionTruncated) {
        this.flows = List.copyOf(flows);
        this.nodeIds = Set.copyOf(nodeIds);
        this.communities = List.copyOf(communities);
        this.cycles = List.copyOf(cycles);
        this.centrality = Map.copyOf(centrality);
        this.identities = Map.copyOf(identities);
        this.facts = facts == null ? Map.of() : Map.copyOf(facts);
        this.criteria = Objects.requireNonNull(criteria, "criteria");
        this.cycleDetectionTruncated = cycleDetectionTruncated;
    }

    public List<FraudRingFlow> flows() { return flows; }
    public Set<Long> nodeIds() { return nodeIds; }
    public List<Long> sortedNodeIds() { return new java.util.ArrayList<>(new TreeSet<>(nodeIds)); }
    public List<Community> communities() { return communities; }
    public List<MoneyCycle> cycles() { return cycles; }
    public Map<Long, CentralityMeasures> centrality() { return centrality; }
    public Map<Long, CustomerIdentity> identities() { return identities; }
    public Map<Long, CustomerRiskFact> facts() { return facts; }
    public FraudRingCriteria criteria() { return criteria; }
    /** True when cycle output/work caps prevented exhaustive enumeration. */
    public boolean cycleDetectionTruncated() { return cycleDetectionTruncated; }

    public boolean isEmpty() { return nodeIds.isEmpty(); }
    public int nodeCount() { return nodeIds.size(); }
    public int edgeCount() { return flows.size(); }

    public CustomerIdentity identity(long id) { return identities.get(id); }

    public CentralityMeasures centralityOf(long id) {
        return centrality.getOrDefault(id, CentralityMeasures.isolated(id));
    }

    public CustomerRiskFact factOf(long id) {
        return facts.getOrDefault(id, CustomerRiskFact.unknown(id));
    }

    /** Which community the customer belongs to (-1 when in none — impossible here). */
    public int communityOf(long customerId) {
        for (Community c : communities) if (c.involves(customerId)) return (int) c.communityId();
        return -1;
    }

    /** All cycles passing through the customer, longest first (deterministic order). */
    public List<MoneyCycle> cyclesInvolving(long customerId) {
        return cycles.stream().filter(c -> c.involves(customerId)).toList();
    }

    public long cycleCount() { return cycles.size(); }
    public long ringSizedCommunityCount() {
        return communities.stream().filter(c -> c.size() >= Community.MIN_RING_SIZE).count();
    }

    /** Volume of transfers with both endpoints inside the given community. */
    public java.math.BigDecimal communityVolume(Community community) {
        Set<Long> members = new TreeSet<>(community.memberIds());
        java.math.BigDecimal total = java.math.BigDecimal.ZERO;
        for (FraudRingFlow f : flows) {
            if (f.transferCount() == 0) continue;
            long from = f.outgoing() ? f.customer().id() : f.counterpart().id();
            long to = f.outgoing() ? f.counterpart().id() : f.customer().id();
            if (members.contains(from) && members.contains(to)) total = total.add(f.totalAmount());
        }
        return total.setScale(2, java.math.RoundingMode.HALF_UP);
    }

    /** Fresh view over the members of every ring-sized community (UI table rows). */
    public Map<Integer, List<CustomerIdentity>> communitiesByIdentity() {
        Map<Integer, List<CustomerIdentity>> out = new TreeMap<>();
        for (Community c : communities) out.put((int) c.communityId(), c.members());
        return out;
    }

    /** Aggregated view: one row per customer with everything the UI table needs. */
    public List<CustomerNodeView> nodeViews() {
        java.util.List<CustomerNodeView> views = new java.util.ArrayList<>();
        for (Long id : sortedNodeIds()) {
            CustomerIdentity who = identities.get(id);
            if (who == null) continue;
            long sent = 0, received = 0;
            for (FraudRingFlow f : flows) {
                // each row is written from the customer's own side: outgoing rows
                // count what this customer SENT, incoming rows what it RECEIVED —
                // both directions must be summed, not just rows where this customer
                // is the record's owner.
                if (f.outgoing() && f.customer().id() == id) sent += f.transferCount();
                if (!f.outgoing() && f.customer().id() == id) received += f.transferCount();
                if (f.outgoing() && f.counterpart().id() == id) received += f.transferCount();
                if (!f.outgoing() && f.counterpart().id() == id) sent += f.transferCount();
            }
            views.add(new CustomerNodeView(id, who.displayName(), who.banned(), sent, received,
                    centralityOf(id), communityOf(id), cyclesInvolving(id).size(),
                    factOf(id).sharedPhoneCount(), factOf(id).accountAgeDaysOrUnknown()));
        }
        return views;
    }

    /**
     * Flat per-customer row for the UI and the risk layer — one place where the
     * structural outputs (centrality/community/cycles) meet the behavioural
     * facts (velocity, tenure, shared phone).
     */
    public record CustomerNodeView(long customerId,
                                   String displayName,
                                   boolean banned,
                                   long transfersSent,
                                   long transfersReceived,
                                   CentralityMeasures centrality,
                                   int communityId,
                                   int cycleCount,
                                   int sharedPhoneCount,
                                   int accountAgeDays) { }

    /** The complete analysis output snapshot for serialisation (Phase 3 fills scores). */
    public record GraphSummary(int nodeCount, int edgeCount, int communityCount,
                               int cycleCount, boolean truncated,
                               FraudRingCriteria criteria) { }

    public GraphSummary summary(boolean truncated) {
        return new GraphSummary(nodeCount(), edgeCount(), communities.size(), cycles.size(), truncated, criteria);
    }
}
