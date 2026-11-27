package com.bank.graph.domain;

/** The kinds of link the customer graph can contain. */
public enum RelationshipType {

    /** Aggregated COMPLETED transfers from one customer to another (directed). */
    MONEY_TRANSFER("Money transfer", true),

    /** Two customers registered with the same phone number (undirected lead, not proof). */
    SHARED_PHONE("Shared phone", false);

    private final String label;
    private final boolean directed;

    RelationshipType(String label, boolean directed) {
        this.label = label;
        this.directed = directed;
    }

    public String label() { return label; }
    public boolean isDirected() { return directed; }
}
