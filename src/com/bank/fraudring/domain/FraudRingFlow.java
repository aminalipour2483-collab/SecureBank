package com.bank.fraudring.domain;

import java.util.List;
import java.util.Objects;

/**
 * Source row for the fraud-ring analysis: the customer itself plus one aggregated
 * transfer flow it participates in (already summed by the database), exactly the
 * shape {@link com.bank.graph.domain.TransferFlow} carries for the customer
 * graph, plus the account tenure signals the risk rules need.
 *
 * <p>Deliberately reuses plain domain types (no JDBC, no Swing): the analyzer
 * must be testable with hand-built rows, mirroring how CustomerGraphBuilder is
 * tested through CustomerGraphTest.
 *
 * @param customer        the customer the node describes (never null)
 * @param counterpart     the other side of the flow (never null, never equal to customer)
 * @param outgoing        true when customer sent money to counterpart, false when it received
 * @param transferCount   completed transfers on this direction
 * @param totalAmount     summed amount on this direction
 * @param lastActivity    most recent transfer on this direction, null when count is 0
 */
public record FraudRingFlow(com.bank.graph.domain.CustomerIdentity customer,
                            com.bank.graph.domain.CustomerIdentity counterpart,
                            boolean outgoing,
                            long transferCount,
                            java.math.BigDecimal totalAmount,
                            java.time.LocalDateTime lastActivity) {

    public FraudRingFlow {
        Objects.requireNonNull(customer, "customer");
        Objects.requireNonNull(counterpart, "counterpart");
        Objects.requireNonNull(totalAmount, "totalAmount");
        if (transferCount < 0) throw new IllegalArgumentException("transferCount must not be negative");
        if (customer.id() == counterpart.id()) {
            throw new IllegalArgumentException("A transfer flow never links a customer to itself");
        }
        totalAmount = totalAmount.setScale(2, java.math.RoundingMode.HALF_UP);
    }
}
