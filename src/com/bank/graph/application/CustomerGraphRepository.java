package com.bank.graph.application;

import com.bank.graph.domain.GraphCriteria;
import com.bank.graph.domain.GraphSourceData;
import com.bank.graph.domain.TransferRecord;

import java.util.List;
import java.util.Set;

/**
 * Port the graph use cases need from persistence. Implemented in the infrastructure layer;
 * the application layer never sees SQL.
 */
public interface CustomerGraphRepository {

    /**
     * Reads transfer flows and shared-phone members from a single consistent snapshot.
     * Implementations must bound their result sizes using {@link GraphCriteria#maxEdges()}.
     */
    GraphSourceData loadGraphSource(GraphCriteria criteria);

    /**
     * Newest-first individual transfers (any status) where a sender or receiver is in {@code customerIds}.
     *
     * @param limit maximum rows to return
     */
    List<TransferRecord> findTransfersInvolving(Set<Long> customerIds, int limit);
}
