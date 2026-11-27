package com.bank.graph.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Set;

/**
 * A relationship between two customers.
 *
 * <p>For directed types, {@code fromId} is the sender and {@code toId} the receiver. For
 * undirected types the ids are normalised so {@code fromId < toId}.
 */
public record RelationshipEdge(RelationshipType type,
                               long fromId,
                               long toId,
                               long transferCount,
                               BigDecimal totalAmount,
                               LocalDateTime lastActivity,
                               String evidence) {

    public RelationshipEdge {
        Objects.requireNonNull(type, "type");
        totalAmount = totalAmount == null ? BigDecimal.ZERO : totalAmount;
        evidence = evidence == null ? "" : evidence;
        if (fromId == toId) throw new IllegalArgumentException("A customer cannot be related to itself");
        if (!type.isDirected() && fromId > toId) {
            long swap = fromId; fromId = toId; toId = swap;
        }
    }

    public boolean involvesAny(Set<Long> customerIds) {
        return customerIds.contains(fromId) || customerIds.contains(toId);
    }
}
