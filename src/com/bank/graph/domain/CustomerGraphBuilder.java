package com.bank.graph.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Pure domain service that turns repository rows into a {@link CustomerGraph}.
 * It knows nothing about SQL, Swing, or the clock.
 *
 * <p>Rules:
 * <ul>
 *   <li>Customers appear only if they have at least one relationship.</li>
 *   <li>A transfer flow between a customer and themselves is not a relationship.</li>
 *   <li>Duplicate flows for the same ordered pair are merged; A&rarr;B and B&rarr;A stay separate edges.</li>
 *   <li>Shared-phone groups become pairwise undirected edges, but only for groups of
 *       2..{@value #MAX_SHARED_GROUP_SIZE} customers. Larger groups (a shared switchboard, a placeholder number)
 *       are counted as omitted instead of exploding into O(n&sup2;) edges.</li>
 *   <li>If the repository truncated its phone rows, the final group may be incomplete and is dropped.</li>
 * </ul>
 */
public final class CustomerGraphBuilder {

    public static final int MAX_SHARED_GROUP_SIZE = 10;

    private CustomerGraphBuilder() { }

    public static CustomerGraph build(GraphSourceData source, GraphCriteria criteria, LocalDateTime generatedAt) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(criteria, "criteria");

        boolean truncated = source.flowsTruncated();
        Map<Long, CustomerIdentity> identities = new HashMap<>();
        Map<Long, long[]> transferCounts = new HashMap<>();      // id -> {sent, received}
        List<RelationshipEdge> edges = new ArrayList<>();

        // ---- money transfers ------------------------------------------------------------------
        Map<List<Long>, TransferAccumulator> merged = new LinkedHashMap<>();
        for (TransferFlow flow : source.flows()) {
            if (flow.sender().id() == flow.receiver().id() || flow.transferCount() == 0) continue;
            merged.computeIfAbsent(List.of(flow.sender().id(), flow.receiver().id()),
                    k -> new TransferAccumulator(flow.sender(), flow.receiver())).add(flow);
        }
        for (TransferAccumulator acc : merged.values()) {
            identities.putIfAbsent(acc.sender.id(), acc.sender);
            identities.putIfAbsent(acc.receiver.id(), acc.receiver);
            transferCounts.computeIfAbsent(acc.sender.id(), k -> new long[2])[0] += acc.count;
            transferCounts.computeIfAbsent(acc.receiver.id(), k -> new long[2])[1] += acc.count;
            edges.add(new RelationshipEdge(RelationshipType.MONEY_TRANSFER, acc.sender.id(), acc.receiver.id(),
                    acc.count, acc.total, acc.last, ""));
        }

        // ---- shared phone numbers -------------------------------------------------------------
        int omittedGroups = 0;
        if (criteria.includeSharedPhone()) {
            Map<String, Map<Long, CustomerIdentity>> groups = new LinkedHashMap<>();
            for (SharedPhoneMember m : source.phoneMembers()) {
                if (m.normalizedPhone().isBlank()) continue;
                groups.computeIfAbsent(m.normalizedPhone(), k -> new LinkedHashMap<>())
                        .putIfAbsent(m.customer().id(), m.customer());
            }
            List<String> phones = new ArrayList<>(groups.keySet());
            if (source.phoneMembersTruncated() && !phones.isEmpty()) {
                phones.remove(phones.size() - 1);          // last group may be cut short
                truncated = true;
            }
            List<RelationshipEdge> phoneEdges = new ArrayList<>();
            for (String phone : phones) {
                List<CustomerIdentity> members = new ArrayList<>(groups.get(phone).values());
                if (members.size() < 2) continue;
                if (members.size() > MAX_SHARED_GROUP_SIZE) { omittedGroups++; continue; }
                members.sort(Comparator.comparingLong(CustomerIdentity::id));
                String evidence = "Shared phone ending " + lastDigits(phone);
                for (int i = 0; i < members.size(); i++) {
                    for (int j = i + 1; j < members.size(); j++) {
                        phoneEdges.add(new RelationshipEdge(RelationshipType.SHARED_PHONE,
                                members.get(i).id(), members.get(j).id(), 0, BigDecimal.ZERO, null, evidence));
                    }
                }
                members.forEach(m -> identities.putIfAbsent(m.id(), m));
            }
            if (phoneEdges.size() > criteria.maxEdges()) {
                phoneEdges = new ArrayList<>(phoneEdges.subList(0, criteria.maxEdges()));
                truncated = true;
            }
            edges.addAll(phoneEdges);
        }

        // Drop identities that lost every edge after the cap above.
        Set<Long> connected = new HashSet<>();
        Map<Long, Set<Long>> neighbours = new HashMap<>();
        for (RelationshipEdge e : edges) {
            connected.add(e.fromId());
            connected.add(e.toId());
            neighbours.computeIfAbsent(e.fromId(), k -> new HashSet<>()).add(e.toId());
            neighbours.computeIfAbsent(e.toId(), k -> new HashSet<>()).add(e.fromId());
        }

        List<CustomerNode> nodes = new ArrayList<>();
        for (Long id : connected) {
            CustomerIdentity who = identities.get(id);
            long[] counts = transferCounts.getOrDefault(id, new long[2]);
            nodes.add(new CustomerNode(id, who.displayName(), who.banned(),
                    counts[0], counts[1], neighbours.get(id).size()));
        }
        nodes.sort(Comparator.comparingLong(CustomerNode::id));
        edges.sort(Comparator.comparing(RelationshipEdge::type)
                .thenComparingLong(RelationshipEdge::fromId)
                .thenComparingLong(RelationshipEdge::toId));

        return new CustomerGraph(nodes, edges, truncated, omittedGroups, criteria, generatedAt);
    }

    private static String lastDigits(String phone) {
        return phone.length() <= 4 ? phone : phone.substring(phone.length() - 4);
    }

    private static final class TransferAccumulator {
        final CustomerIdentity sender;
        final CustomerIdentity receiver;
        long count;
        BigDecimal total = BigDecimal.ZERO;
        LocalDateTime last;

        TransferAccumulator(CustomerIdentity sender, CustomerIdentity receiver) {
            this.sender = sender;
            this.receiver = receiver;
        }

        void add(TransferFlow flow) {
            count += flow.transferCount();
            total = total.add(flow.totalAmount());
            if (flow.lastActivity() != null && (last == null || flow.lastActivity().isAfter(last))) {
                last = flow.lastActivity();
            }
        }
    }
}
