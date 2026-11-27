package com.bank.graph.domain;

/**
 * A customer in the graph.
 *
 * @param transfersSent     completed transfers this customer sent to other customers
 * @param transfersReceived completed transfers this customer received from other customers
 * @param connections       distinct other customers linked by any relationship type
 */
public record CustomerNode(long id,
                           String displayName,
                           boolean banned,
                           long transfersSent,
                           long transfersReceived,
                           int connections) { }
