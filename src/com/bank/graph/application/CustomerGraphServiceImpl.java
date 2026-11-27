package com.bank.graph.application;

import com.bank.exception.BankException;
import com.bank.graph.domain.CustomerGraph;
import com.bank.graph.domain.CustomerGraphBuilder;
import com.bank.graph.domain.GraphCriteria;
import com.bank.graph.domain.GraphSourceData;
import com.bank.graph.domain.RelationshipType;
import com.bank.graph.domain.TransferRecord;
import com.bank.security.AdminGuard;
import com.bank.security.AdminPrincipal;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Logger;

public class CustomerGraphServiceImpl implements CustomerGraphService {

    private static final Logger AUDIT = Logger.getLogger("com.bank.audit.customer-graph");

    private final CustomerGraphRepository repository;
    private final Clock clock;

    public CustomerGraphServiceImpl(CustomerGraphRepository repository, Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public CustomerGraph generateGraph(AdminPrincipal admin, GraphCriteria criteria) {
        AdminGuard.require(admin);
        Objects.requireNonNull(criteria, "criteria");

        GraphSourceData source = repository.loadGraphSource(criteria);
        CustomerGraph graph = CustomerGraphBuilder.build(source, criteria, LocalDateTime.now(clock));

        // Business audit record: who looked at the relationship graph, with what scope, and what came back.
        // Customer names and phone numbers are deliberately not logged.
        AUDIT.info("GRAPH_GENERATED admin=" + admin.username()
                + " period=" + criteria.describePeriod()
                + " sharedPhone=" + criteria.includeSharedPhone()
                + " customers=" + graph.nodes().size()
                + " transferLinks=" + graph.countEdges(RelationshipType.MONEY_TRANSFER)
                + " phoneLinks=" + graph.countEdges(RelationshipType.SHARED_PHONE)
                + " truncated=" + graph.isTruncated());
        return graph;
    }

    @Override
    public List<TransferRecord> getTransfersInvolving(AdminPrincipal admin, Set<Long> customerIds) {
        AdminGuard.require(admin);
        if (customerIds == null || customerIds.isEmpty()) return List.of();
        if (customerIds.size() > MAX_DETAIL_CUSTOMERS) {
            throw new BankException("Select at most " + MAX_DETAIL_CUSTOMERS
                    + " customers to inspect their transfers.");
        }
        List<TransferRecord> rows = repository.findTransfersInvolving(Set.copyOf(customerIds), MAX_DETAIL_ROWS);
        AUDIT.info("TRANSFERS_INSPECTED admin=" + admin.username()
                + " customerIds=" + customerIds.size() + " rows=" + rows.size());
        return rows;
    }
}
