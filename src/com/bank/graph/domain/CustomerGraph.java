package com.bank.graph.domain;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/** An immutable snapshot of customers and the relationships between them. */
public final class CustomerGraph {

    private final List<CustomerNode> nodes;
    private final List<RelationshipEdge> edges;
    private final boolean truncated;
    private final int omittedSharedGroups;
    private final GraphCriteria criteria;
    private final LocalDateTime generatedAt;
    private final Map<Long, CustomerNode> nodeIndex;

    CustomerGraph(List<CustomerNode> nodes, List<RelationshipEdge> edges, boolean truncated,
                  int omittedSharedGroups, GraphCriteria criteria, LocalDateTime generatedAt) {
        this.nodes = List.copyOf(nodes);
        this.edges = List.copyOf(edges);
        this.truncated = truncated;
        this.omittedSharedGroups = omittedSharedGroups;
        this.criteria = criteria;
        this.generatedAt = generatedAt;
        Map<Long, CustomerNode> index = new HashMap<>();
        for (CustomerNode node : this.nodes) index.put(node.id(), node);
        this.nodeIndex = Map.copyOf(index);
    }

    public List<CustomerNode> nodes() { return nodes; }
    public List<RelationshipEdge> edges() { return edges; }
    public boolean isEmpty() { return nodes.isEmpty(); }
    public GraphCriteria criteria() { return criteria; }
    public LocalDateTime generatedAt() { return generatedAt; }

    /** True when a row/edge limit cut the result: the graph is a strongest-links subset. */
    public boolean isTruncated() { return truncated; }

    /** Shared-phone groups skipped because they were too large to be a meaningful pairwise link. */
    public int omittedSharedGroups() { return omittedSharedGroups; }

    public Optional<CustomerNode> node(long id) { return Optional.ofNullable(nodeIndex.get(id)); }

    public long bannedCount() { return nodes.stream().filter(CustomerNode::banned).count(); }

    public long countEdges(RelationshipType type) {
        return edges.stream().filter(e -> e.type() == type).count();
    }

    public long totalTransfers() {
        return edges.stream().filter(e -> e.type() == RelationshipType.MONEY_TRANSFER)
                .mapToLong(RelationshipEdge::transferCount).sum();
    }

    public List<RelationshipEdge> edgesInvolving(Set<Long> customerIds) {
        return edges.stream().filter(e -> e.involvesAny(customerIds)).toList();
    }

    /**
     * Groups of customers connected, directly or indirectly, by any relationship (direction ignored).
     * Largest group first; ties broken by lowest customer id, so the order is deterministic.
     */
    public List<List<Long>> connectedComponents() {
        Map<Long, Long> parent = new HashMap<>();
        for (CustomerNode n : nodes) parent.put(n.id(), n.id());
        for (RelationshipEdge e : edges) {
            long a = find(parent, e.fromId());
            long b = find(parent, e.toId());
            if (a != b) parent.put(Math.max(a, b), Math.min(a, b));
        }
        Map<Long, List<Long>> groups = new TreeMap<>();
        for (CustomerNode n : nodes) {
            groups.computeIfAbsent(find(parent, n.id()), k -> new ArrayList<>()).add(n.id());
        }
        List<List<Long>> result = new ArrayList<>(groups.values());
        result.forEach(g -> g.sort(Comparator.naturalOrder()));
        result.sort(Comparator.<List<Long>>comparingInt(List::size).reversed()
                .thenComparingLong(g -> g.get(0)));
        return result;
    }

    private static long find(Map<Long, Long> parent, long id) {
        long root = id;
        while (parent.get(root) != root) root = parent.get(root);
        while (parent.get(id) != root) {            // path compression
            long next = parent.get(id);
            parent.put(id, root);
            id = next;
        }
        return root;
    }
}
