package com.bank.graph.domain;

import java.util.Objects;

/** Source row: one customer holding a (normalised) phone number that other customers also hold. */
public record SharedPhoneMember(String normalizedPhone, CustomerIdentity customer) {

    public SharedPhoneMember {
        Objects.requireNonNull(normalizedPhone, "normalizedPhone");
        Objects.requireNonNull(customer, "customer");
    }
}
