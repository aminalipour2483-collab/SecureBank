package com.bank.fraudring.domain;

import com.bank.graph.domain.CustomerIdentity;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Pure domain engine behind "Analyze Fraud Rings".
 *
 * <p>Input: the flow rows and shared-phone groups of one consistent read-only
 * snapshot. Output: communities (candidate rings), money cycles, centrality.
 * This class knows nothing about SQL, Swing, or the clock — it is exhaustively
 * unit-testable on hand-built graphs, exactly like {@code CustomerGraphBuilder}.
 *
 * <p><b>Determinism contract.</b> Nodes and candidate communities are visited in
 * ascending id order; the Isolation Forest uses a fixed seed; PageRank starts
 * from a uniform vector and iterates to a fixed tolerance; communities are
 * sorted by (size desc, lowest member id asc). Identical snapshots therefore
 * produce identical results.
 *
 * <p><b>Bounded work.</b> Cycle candidates are restricted to non-trivial strongly
 * connected components, then depth-capped at {@link MoneyCycle#MAX_HOPS} and
 * bounded by explicit expansion/output budgets. The result marks incomplete
 * cycle enumeration rather than silently claiming that a capped list is complete.
 */
public final class FraudRingAnalyzer {

    private FraudRingAnalyzer() { }

    /** Hard limits prevent dense graphs from exhausting memory during cycle enumeration. */
    static final int MAX_CYCLE_RESULTS = 5_000;
    static final long MAX_CYCLE_EDGE_EXPANSIONS = 500_000L;

    // ─────────────────────────────────────────────────────────────────────────
    // Public entry point
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Runs the full structural analysis over one snapshot.
     *
     * @param flows        aggregated transfer flow rows (either direction, both included)
     * @param phoneGroups  normalised phone → members, only groups of 2..MAX_SHARED_GROUP_SIZE (larger
     *                     groups are deliberately dropped: a shared switchboard is not a relationship)
     * @param facts        per-customer tenure facts; missing ids read as unknown
     * @param criteria     scope/limits of the analysis (SUBSET/SINGLE filtering happens here too)
     */
    public static FraudRingGraph analyze(List<FraudRingFlow> flows,
                                         Map<String, List<CustomerIdentity>> phoneGroups,
                                         Map<Long, CustomerRiskFact> facts,
                                         FraudRingCriteria criteria) {
        Objects2.requireNonNull(flows, "flows");
        Objects2.requireNonNull(phoneGroups, "phoneGroups");
        Objects2.requireNonNull(facts, "facts");
        Objects2.requireNonNull(criteria, "criteria");

        // 1 ── apply the scope filter. FULL keeps everything. SUBSET keeps flows with
        //      BOTH endpoints inside the selection. SINGLE keeps the chosen customer's
        //      flows plus their counterparties (a one-hop ego network): the point of
        //      SINGLE is "who does this customer move money with", and cycles need the
        //      other side of each edge to exist. The ego customer's community/cycle
        //      membership is still computed over that ego network only.
        List<FraudRingFlow> scoped = new ArrayList<>();
        for (FraudRingFlow f : flows) {
            if (f.transferCount() == 0) continue;
            switch (criteria.scope()) {
                case FULL -> scoped.add(f);
                case SUBSET -> {
                    if (criteria.customerIds().contains(f.customer().id())
                            && criteria.customerIds().contains(f.counterpart().id())) {
                        scoped.add(f);
                    }
                }
                case SINGLE -> {
                    long chosen = criteria.customerIds().iterator().next();
                    if (f.customer().id() == chosen || f.counterpart().id() == chosen) {
                        scoped.add(f);
                    }
                }
            }
        }

        // 2 ── build the two adjacency views (direction for cycles/PageRank,
        //      undirected for communities/degree/betweenness)
        Set<Long> scopeIds = scopeFilter(scoped, phoneGroups, criteria);
        TreeSet<Long> nodeIds = new TreeSet<>(scopeIds);
        Map<Long, CustomerIdentity> identities = new TreeMap<>();
        for (FraudRingFlow f : scoped) {
            identities.putIfAbsent(f.customer().id(), f.customer());
            identities.putIfAbsent(f.counterpart().id(), f.counterpart());
        }
        // a SINGLE scope's chosen customer may appear on no flow row at all
        // (no transfers yet): still keep a placeholder identity so community
        // building can never see a null member. Phone groups may also carry
        // identities the flows never mention.
        for (List<CustomerIdentity> group : phoneGroups.values()) {
            for (CustomerIdentity member : group) identities.putIfAbsent(member.id(), member);
        }
        if (criteria.scope() == FraudRingScope.SINGLE) {
            long chosen = criteria.customerIds().iterator().next();
            identities.putIfAbsent(chosen, CustomerIdentity.of(chosen, "", "", false));
        }

        // directed: from → list of (to, count, amount, last)
        Map<Long, Map<Long, FlowEdge>> directed = new TreeMap<>();
        for (FraudRingFlow f : scoped) {
            long from = f.outgoing() ? f.customer().id() : f.counterpart().id();
            long to = f.outgoing() ? f.counterpart().id() : f.customer().id();
            directed.computeIfAbsent(from, k -> new TreeMap<>())
                    .put(to, new FlowEdge(f.transferCount(), f.totalAmount(), f.lastActivity()));
        }

        // undirected adjacency with merged edge weight (count sum)
        Map<Long, TreeMap<Long, Long>> undirected = new TreeMap<>();
        for (FraudRingFlow f : scoped) {
            long a = f.customer().id();
            long b = f.counterpart().id();
            undirected.computeIfAbsent(a, k -> new TreeMap<>()).merge(b, f.transferCount(), Long::sum);
            undirected.computeIfAbsent(b, k -> new TreeMap<>()).merge(a, f.transferCount(), Long::sum);
        }

        // 3 ── algorithms (all deterministic; see class javadoc)
        List<Community> communities = detectCommunities(nodeIds, undirected, phoneGroups,
                scopeIds, identities, scoped);
        CycleDetectionResult cycleResult = detectCycles(nodeIds, directed, identities);
        Map<Long, CentralityMeasures> centrality = computeCentrality(nodeIds, undirected, directed);

        return new FraudRingGraph(scoped, nodeIds, communities, cycleResult.cycles(), centrality, identities,
                facts, criteria, cycleResult.truncated());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Community detection — deterministic weighted Louvain local-moving phase
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Performs the modularity-gain local-moving phase of Louvain on a weighted,
     * undirected projection of transfers plus shared-phone links. Nodes are
     * visited in stable id order and ties retain the current community, making
     * the result reproducible. This is the local-moving phase (not a claim that
     * a full multi-level Louvain/Leiden hierarchy is implemented).
     */
    private static List<Community> detectCommunities(TreeSet<Long> nodeIds,
                                                      Map<Long, TreeMap<Long, Long>> undirected,
                                                      Map<String, List<CustomerIdentity>> phoneGroups,
                                                      Set<Long> scopeIds,
                                                      Map<Long, CustomerIdentity> identities,
                                                      List<FraudRingFlow> flows) {
        Map<Long, TreeMap<Long, Double>> weighted = new TreeMap<>();
        for (Long id : nodeIds) weighted.put(id, new TreeMap<>());
        for (Map.Entry<Long, TreeMap<Long, Long>> row : undirected.entrySet()) {
            for (Map.Entry<Long, Long> edge : row.getValue().entrySet()) {
                weighted.computeIfAbsent(row.getKey(), ignored -> new TreeMap<>())
                        .put(edge.getKey(), edge.getValue().doubleValue());
            }
        }
        // Shared-phone identity links contribute a small unit weight; they are
        // evidence, not equivalent to a completed money transfer.
        for (List<CustomerIdentity> group : phoneGroups.values()) {
            List<Long> ids = group.stream().map(CustomerIdentity::id)
                    .filter(scopeIds::contains).sorted().toList();
            for (int i = 0; i < ids.size(); i++) {
                for (int j = i + 1; j < ids.size(); j++) {
                    weighted.get(ids.get(i)).merge(ids.get(j), 1d, Double::sum);
                    weighted.get(ids.get(j)).merge(ids.get(i), 1d, Double::sum);
                }
            }
        }

        Map<Long, Double> degree = new TreeMap<>();
        double totalDegree = 0d;
        for (Long id : nodeIds) {
            double value = weighted.getOrDefault(id, new TreeMap<>()).values()
                    .stream().mapToDouble(Double::doubleValue).sum();
            degree.put(id, value);
            totalDegree += value;
        }
        Map<Long, Long> label = new TreeMap<>();
        Map<Long, Double> communityTotals = new TreeMap<>();
        for (Long id : nodeIds) {
            label.put(id, id);
            communityTotals.put(id, degree.getOrDefault(id, 0d));
        }

        if (totalDegree > 0d) {
            for (int sweep = 0; sweep < 50; sweep++) {
                boolean changed = false;
                for (Long id : nodeIds) {
                    double nodeDegree = degree.getOrDefault(id, 0d);
                    if (nodeDegree <= 0d) continue;
                    long oldCommunity = label.get(id);
                    communityTotals.merge(oldCommunity, -nodeDegree, Double::sum);

                    Map<Long, Double> weightsByCommunity = new TreeMap<>();
                    for (Map.Entry<Long, Double> edge : weighted.getOrDefault(id, new TreeMap<>()).entrySet()) {
                        Long neighborCommunity = label.get(edge.getKey());
                        if (neighborCommunity != null) {
                            weightsByCommunity.merge(neighborCommunity, edge.getValue(), Double::sum);
                        }
                    }

                    long bestCommunity = oldCommunity;
                    double bestGain = weightsByCommunity.getOrDefault(oldCommunity, 0d)
                            - nodeDegree * communityTotals.getOrDefault(oldCommunity, 0d) / totalDegree;
                    for (Map.Entry<Long, Double> candidate : weightsByCommunity.entrySet()) {
                        long candidateId = candidate.getKey();
                        double gain = candidate.getValue()
                                - nodeDegree * communityTotals.getOrDefault(candidateId, 0d) / totalDegree;
                        if (gain > bestGain + 1e-12) {
                            bestGain = gain;
                            bestCommunity = candidateId;
                        }
                    }
                    label.put(id, bestCommunity);
                    communityTotals.merge(bestCommunity, nodeDegree, Double::sum);
                    if (bestCommunity != oldCommunity) changed = true;
                }
                if (!changed) break;
            }
        }

        Map<Long, List<Long>> groups = new TreeMap<>();
        for (Long id : nodeIds) groups.computeIfAbsent(label.get(id), ignored -> new ArrayList<>()).add(id);
        List<Community> communities = new ArrayList<>();
        for (List<Long> members : groups.values()) {
            communities.add(buildCommunity(members, undirected, phoneGroups, identities, flows));
        }
        communities.sort(Comparator.<Community>comparingInt(Community::size).reversed()
                .thenComparing(c -> c.memberIds().get(0)));
        for (int i = 0; i < communities.size(); i++) communities.set(i, renumber(communities.get(i), i));
        return communities;
    }

    private static Community renumber(Community c, int newId) {
        return new Community(newId, c.members(), c.density(), c.internalFlows(), c.internalVolume(), c.sharedPhones());
    }

    private static Community buildCommunity(List<Long> memberIds,
                                            Map<Long, TreeMap<Long, Long>> undirected,
                                            Map<String, List<CustomerIdentity>> phoneGroups,
                                            Map<Long, CustomerIdentity> identities,
                                            List<FraudRingFlow> flows) {
        // phone-only members may never appear on a flow row: carry their identity
        // from the phone groups when the flow-based map lacks it.
        Map<Long, CustomerIdentity> known = new TreeMap<>(identities);
        for (List<CustomerIdentity> group : phoneGroups.values()) {
            for (CustomerIdentity member : group) known.putIfAbsent(member.id(), member);
        }
        List<CustomerIdentity> members = memberIds.stream()
                .sorted().map(known::get).toList();
        int n = memberIds.size();
        int internalFlows = 0;
        for (Long id : memberIds) {
            TreeMap<Long, Long> nbrs = undirected.getOrDefault(id, new TreeMap<>());
            for (Long nbr : nbrs.keySet()) {
                if (nbr > id && memberIds.contains(nbr)) internalFlows++;  // count each edge once
            }
        }
        double density = n > 1 ? (2.0 * internalFlows) / (n * (n - 1.0)) : 0d;
        BigDecimal volume = BigDecimal.ZERO;
        List<String> sharedPhones = new ArrayList<>();
        Set<Long> memberSet = new HashSet<>(memberIds);
        for (FraudRingFlow flow : flows) {
            long from = flow.outgoing() ? flow.customer().id() : flow.counterpart().id();
            long to = flow.outgoing() ? flow.counterpart().id() : flow.customer().id();
            if (memberSet.contains(from) && memberSet.contains(to)) {
                volume = volume.add(flow.totalAmount());
            }
        }
        for (Map.Entry<String, List<CustomerIdentity>> entry : phoneGroups.entrySet()) {
            long inside = entry.getValue().stream().map(CustomerIdentity::id).filter(memberSet::contains).count();
            if (inside >= 2) sharedPhones.add(entry.getKey());
        }
        return new Community(0, members, density, internalFlows, volume, sharedPhones);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Cycle detection — SCC-pruned, explicitly budgeted bounded enumeration
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * First restricts work to non-trivial strongly connected components using
     * iterative Kosaraju (O(V+E)); then enumerates canonical simple cycles up to
     * MAX_HOPS. A global edge-expansion and output budget makes worst-case work
     * explicit on dense graphs. Results report whether either budget truncated
     * enumeration; a truncated result is never presented as exhaustive.
     */
    private static CycleDetectionResult detectCycles(TreeSet<Long> nodeIds,
                                                      Map<Long, Map<Long, FlowEdge>> directed,
                                                      Map<Long, CustomerIdentity> identities) {
        List<MoneyCycle> cycles = new ArrayList<>();
        CycleBudget budget = new CycleBudget();
        Map<Long, TreeSet<Long>> reverse = new TreeMap<>();
        for (Long id : nodeIds) reverse.put(id, new TreeSet<>());
        for (Map.Entry<Long, Map<Long, FlowEdge>> entry : directed.entrySet()) {
            for (Long target : entry.getValue().keySet()) {
                reverse.computeIfAbsent(target, ignored -> new TreeSet<>()).add(entry.getKey());
            }
        }

        Set<Long> visited = new HashSet<>();
        List<Long> finishOrder = new ArrayList<>(nodeIds.size());
        for (Long start : nodeIds) {
            if (!visited.add(start)) continue;
            ArrayDeque<DfsFrame> stack = new ArrayDeque<>();
            stack.push(new DfsFrame(start, directed.getOrDefault(start, Map.of()).keySet().iterator()));
            while (!stack.isEmpty()) {
                DfsFrame frame = stack.peek();
                if (frame.neighbors().hasNext()) {
                    Long next = frame.neighbors().next();
                    if (visited.add(next)) {
                        stack.push(new DfsFrame(next,
                                directed.getOrDefault(next, Map.of()).keySet().iterator()));
                    }
                } else {
                    finishOrder.add(frame.node());
                    stack.pop();
                }
            }
        }

        visited.clear();
        List<TreeSet<Long>> components = new ArrayList<>();
        for (int i = finishOrder.size() - 1; i >= 0; i--) {
            long start = finishOrder.get(i);
            if (!visited.add(start)) continue;
            TreeSet<Long> component = new TreeSet<>();
            ArrayDeque<Long> stack = new ArrayDeque<>();
            stack.push(start);
            while (!stack.isEmpty()) {
                long current = stack.pop();
                component.add(current);
                for (Long next : reverse.getOrDefault(current, new TreeSet<>()).descendingSet()) {
                    if (visited.add(next)) stack.push(next);
                }
            }
            if (component.size() > 1) components.add(component);
        }
        components.sort(Comparator.comparing(TreeSet::first));

        for (TreeSet<Long> component : components) {
            if (budget.truncated) break;
            for (Long start : component) {
                if (budget.truncated) break;
                dfsCycle(start, start, new ArrayList<>(List.of(start)), new HashSet<>(Set.of(start)),
                        component, directed, identities, cycles, budget);
            }
        }
        cycles.sort(Comparator
                .<MoneyCycle>comparingInt(MoneyCycle::hopCount).reversed()
                .thenComparing(c -> c.memberIds().get(0))
                .thenComparing(c -> c.memberIds().get(Math.min(1, c.memberIds().size() - 1))));
        return new CycleDetectionResult(List.copyOf(cycles), budget.truncated);
    }

    private static void dfsCycle(long start, long current,
                                 List<Long> path, Set<Long> onPath, Set<Long> component,
                                 Map<Long, Map<Long, FlowEdge>> directed,
                                 Map<Long, CustomerIdentity> identities,
                                 List<MoneyCycle> cycles, CycleBudget budget) {
        if (budget.truncated || path.size() > MoneyCycle.MAX_HOPS) return;
        Map<Long, FlowEdge> edges = directed.getOrDefault(current, Map.of());
        for (Map.Entry<Long, FlowEdge> edge : edges.entrySet()) {
            if (!budget.consume()) return;
            long next = edge.getKey();
            if (!component.contains(next)) continue;
            if (next == start) {
                if (path.size() >= MoneyCycle.MIN_HOPS) {
                    cycles.add(toCycle(path, directed, identities));
                    if (cycles.size() >= MAX_CYCLE_RESULTS) {
                        budget.truncated = true;
                        return;
                    }
                }
                continue;
            }
            if (next < start || onPath.contains(next)) continue;
            if (path.size() + 1 > MoneyCycle.MAX_HOPS) continue;
            path.add(next);
            onPath.add(next);
            dfsCycle(start, next, path, onPath, component, directed, identities, cycles, budget);
            path.remove(path.size() - 1);
            onPath.remove(next);
            if (budget.truncated) return;
        }
    }

    private record DfsFrame(long node, Iterator<Long> neighbors) { }
    private record CycleDetectionResult(List<MoneyCycle> cycles, boolean truncated) { }

    private static final class CycleBudget {
        long expansions;
        boolean truncated;
        boolean consume() {
            if (expansions >= MAX_CYCLE_EDGE_EXPANSIONS) {
                truncated = true;
                return false;
            }
            expansions++;
            return true;
        }
    }

    private static MoneyCycle toCycle(List<Long> path,
                                       Map<Long, Map<Long, FlowEdge>> directed,
                                       Map<Long, CustomerIdentity> identities) {
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal min = null;
        LocalDateTime last = null;
        for (int i = 0; i < path.size(); i++) {
            FlowEdge e = directed.get(path.get(i)).get(path.get((i + 1) % path.size()));
            total = total.add(e.amount());
            min = (min == null || e.amount().compareTo(min) < 0) ? e.amount() : min;
            if (e.lastActivity() != null && (last == null || e.lastActivity().isAfter(last))) last = e.lastActivity();
        }
        List<CustomerIdentity> members = path.stream().map(identities::get).toList();
        return new MoneyCycle(members, path.size(), total, min, last);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Centrality — degree, Brandes betweenness, PageRank power iteration
    // ─────────────────────────────────────────────────────────────────────────

    private static Map<Long, CentralityMeasures> computeCentrality(TreeSet<Long> nodeIds,
                                                                   Map<Long, TreeMap<Long, Long>> undirected,
                                                                   Map<Long, Map<Long, FlowEdge>> directed) {
        Map<Long, Integer> degree = new TreeMap<>();
        for (Long id : nodeIds) degree.put(id, undirected.getOrDefault(id, new TreeMap<>()).size());

        // ── Brandes (2001) unweighted undirected betweenness, normalised to 0..1 ──
        double[] betweennessRaw = brandes(nodeIds, undirected);

        // ── PageRank on the directed transfer network: r(v) = 0.15/n + 0.85 · Σ r(u)/d(u) ──
        //    power iteration capped at 40 sweeps, delta < 1e-9; seeded uniform. Deterministic.
        Map<Long, Double> pageRank = pageRank(nodeIds, directed);

        Map<Long, CentralityMeasures> result = new TreeMap<>();
        int i = 0;
        for (Long id : nodeIds) {
            result.put(id, new CentralityMeasures(id, degree.get(id), betweennessRaw[i], pageRank.get(id)));
            i++;
        }
        return result;
    }

    private static double[] brandes(TreeSet<Long> nodeIds, Map<Long, TreeMap<Long, Long>> undirected) {
        List<Long> order = new ArrayList<>(nodeIds);
        int n = order.size();
        double[] betweenness = new double[n];
        if (n < 3) return betweenness;                     // no pair excludes any middle node

        for (int s = 0; s < n; s++) {
            // BFS from order[s]
            List<Integer> stack = new ArrayList<>();
            List<List<Integer>> predecessors = new ArrayList<>(n);
            double[] sigma = new double[n];
            int[] dist = new int[n];
            java.util.Arrays.fill(dist, -1);
            for (int k = 0; k < n; k++) predecessors.add(new ArrayList<>());
            sigma[s] = 1;
            dist[s] = 0;
            java.util.ArrayDeque<Integer> queue = new java.util.ArrayDeque<>();
            queue.add(s);
            while (!queue.isEmpty()) {
                int v = queue.poll();
                stack.add(v);
                for (Map.Entry<Long, Long> nbrEntry : undirected.getOrDefault(order.get(v), new TreeMap<>()).entrySet()) {
                    int w = index(order, nbrEntry.getKey());
                    if (w < 0) continue;
                    if (dist[w] < 0) {
                        dist[w] = dist[v] + 1;
                        queue.add(w);
                    }
                    if (dist[w] == dist[v] + 1) {
                        sigma[w] += sigma[v];
                        predecessors.get(w).add(v);
                    }
                }
            }
            // dependency accumulation
            double[] delta = new double[n];
            for (int j = stack.size() - 1; j >= 0; j--) {
                int w = stack.get(j);
                for (int v : predecessors.get(w)) {
                    double share = sigma[v] > 0 && sigma[w] > 0
                            ? (double) sigma[v] / sigma[w] * (1 + delta[w]) : 0d;
                    delta[v] += share;
                }
                if (w != s) betweenness[w] += delta[w];
            }
        }
        // normalise: undirected pairs counted once → divide by 2; then by (n-1)(n-2)/2
        double norm = ((double) (n - 1) * (n - 2)) / 2d;
        for (int k = 0; k < n; k++) betweenness[k] = norm > 0 ? betweenness[k] / (2d * norm) : 0d;
        return betweenness;
    }

    private static Map<Long, Double> pageRank(TreeSet<Long> nodeIds, Map<Long, Map<Long, FlowEdge>> directed) {
        List<Long> order = new ArrayList<>(nodeIds);
        int n = order.size();
        if (n == 0) return Map.of();
        double damping = 0.85d;
        double base = (1 - damping) / n;
        double[] r = new double[n];
        java.util.Arrays.fill(r, 1.0d / n);
        Map<Long, Double> outWeight = new TreeMap<>();
        for (Long id : order) {
            double total = directed.getOrDefault(id, Map.of()).values().stream()
                    .mapToDouble(edge -> Math.max(0L, edge.transferCount())).sum();
            outWeight.put(id, total);
        }

        for (int sweep = 0; sweep < 100; sweep++) {
            double[] next = new double[n];
            java.util.Arrays.fill(next, base);
            double danglingMass = 0d;
            for (int u = 0; u < n; u++) {
                long source = order.get(u);
                double totalWeight = outWeight.getOrDefault(source, 0d);
                if (totalWeight <= 0d) {
                    danglingMass += r[u];
                    continue;
                }
                for (Map.Entry<Long, FlowEdge> edgeEntry
                        : directed.getOrDefault(source, Map.of()).entrySet()) {
                    int v = index(order, edgeEntry.getKey());
                    if (v < 0) continue;
                    double weight = Math.max(0L, edgeEntry.getValue().transferCount());
                    next[v] += damping * r[u] * (weight / totalWeight);
                }
            }
            double danglingShare = damping * danglingMass / n;
            double delta = 0d;
            for (int k = 0; k < n; k++) {
                next[k] += danglingShare;
                delta += Math.abs(next[k] - r[k]);
            }
            r = next;
            if (delta < 1e-10) break;
        }
        Map<Long, Double> result = new TreeMap<>();
        for (int k = 0; k < n; k++) result.put(order.get(k), r[k]);
        return result;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // helpers
    // ─────────────────────────────────────────────────────────────────────────

    private static int index(List<Long> order, long id) {
        int lo = 0, hi = order.size() - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            long v = order.get(mid);
            if (v == id) return mid;
            if (v < id) lo = mid + 1; else hi = mid - 1;
        }
        return -1;
    }

    /** Which customer ids stay in the analysis after the scope filter. */
    /**
     * Node universe of the analysis. FULL: everyone reachable from the flows or
     * phone groups. SUBSET: exactly the chosen ids (flows outside are dropped
     * upstream). SINGLE: the ego network of the chosen customer is assembled in
     * the filter step, so this returns the chosen id plus its direct
     * counterparties.
     */
    private static Set<Long> scopeFilter(List<FraudRingFlow> scoped,
                                         Map<String, List<CustomerIdentity>> phoneGroups,
                                         FraudRingCriteria criteria) {
        Set<Long> all = new TreeSet<>();
        for (FraudRingFlow f : scoped) {
            all.add(f.customer().id());
            all.add(f.counterpart().id());
        }
        for (List<CustomerIdentity> members : phoneGroups.values()) {
            members.forEach(m -> all.add(m.id()));
        }
        if (criteria.scope() == FraudRingScope.FULL) return all;
        if (criteria.scope() == FraudRingScope.SUBSET) return criteria.customerIds();
        long chosen = criteria.customerIds().iterator().next();
        Set<Long> ego = new TreeSet<>(all);
        ego.retainAll(neighboursOf(chosen, scoped));
        ego.add(chosen);
        return ego;
    }

    private static Set<Long> neighboursOf(long chosen, List<FraudRingFlow> scoped) {
        Set<Long> neighbours = new TreeSet<>();
        for (FraudRingFlow f : scoped) {
            if (f.customer().id() == chosen) neighbours.add(f.counterpart().id());
            if (f.counterpart().id() == chosen) neighbours.add(f.customer().id());
        }
        return neighbours;
    }

    /** One directed edge's aggregated payload. */
    record FlowEdge(long transferCount, BigDecimal amount, LocalDateTime lastActivity) { }

    /** Local alias so the file has no java.util.Objects import clash with the record fields. */
    static final class Objects2 {
        private Objects2() { }
        static <T> T requireNonNull(T o, String name) {
            if (o == null) throw new IllegalArgumentException(name + " must not be null");
            return o;
        }
    }
}
