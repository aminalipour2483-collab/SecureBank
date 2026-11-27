package com.bank.graph.domain;

/** The minimum facts about a customer that the graph needs. */
public record CustomerIdentity(long id, String displayName, boolean banned) {

    public CustomerIdentity {
        displayName = (displayName == null || displayName.isBlank())
                ? "Customer #" + id : displayName.trim();
    }

    public static CustomerIdentity of(long id, String firstName, String lastName, boolean banned) {
        String first = firstName == null ? "" : firstName.trim();
        String last = lastName == null ? "" : lastName.trim();
        return new CustomerIdentity(id, (first + " " + last).trim(), banned);
    }
}
