package com.bank.graph.application;

import com.bank.exception.AccessDeniedException;
import com.bank.graph.domain.CustomerGraph;
import com.bank.graph.domain.GraphCriteria;
import com.bank.graph.domain.TransferRecord;
import com.bank.security.AdminPrincipal;

import java.util.List;
import java.util.Set;

/**
 * Administrator-only use cases for exploring relationships between customers.
 * Every method requires an {@link AdminPrincipal}.
 */
public interface CustomerGraphService {

    /** Most customers whose transfers may be inspected in one drill-down. */
    int MAX_DETAIL_CUSTOMERS = 200;

    /** Most individual transfers returned by one drill-down. */
    int MAX_DETAIL_ROWS = 500;

    /**
     * Builds the customer relationship graph.
     *
     * @throws AccessDeniedException if {@code admin} is null
     */
    CustomerGraph generateGraph(AdminPrincipal admin, GraphCriteria criteria);

    /**
     * Newest-first transfers (all statuses, at most {@link #MAX_DETAIL_ROWS}) involving any of the customers.
     *
     * @throws AccessDeniedException if {@code admin} is null
     * @throws com.bank.exception.BankException if more than {@link #MAX_DETAIL_CUSTOMERS} customers are given
     */
    List<TransferRecord> getTransfersInvolving(AdminPrincipal admin, Set<Long> customerIds);
}
