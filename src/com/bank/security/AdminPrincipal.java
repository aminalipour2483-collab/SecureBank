package com.bank.security;

import java.time.Instant;
import java.util.Objects;

/**
 * Proof that a caller authenticated as a system administrator.
 *
 * <p>Instances are created only by an {@link AdminAuthenticationService}
 * (the constructor is package-private). Application services that expose
 * administrator-only use cases demand one and verify it with
 * {@link AdminGuard#require(AdminPrincipal)}, so the restriction is enforced
 * at the use-case boundary rather than merely by hiding a UI tab.
 *
 * <p>Limitation: both clients run in a single JVM and talk to MySQL directly,
 * so this is an application-level guard, not a network security boundary.
 * A server-side API (Roadmap Phase 3) is required for that.
 */
public final class AdminPrincipal {

    private final String username;
    private final Instant authenticatedAt;

    AdminPrincipal(String username, Instant authenticatedAt) {
        this.username = Objects.requireNonNull(username, "username");
        this.authenticatedAt = Objects.requireNonNull(authenticatedAt, "authenticatedAt");
    }

    public String username() {
        return username;
    }

    public Instant authenticatedAt() {
        return authenticatedAt;
    }

    @Override
    public String toString() {
        return "AdminPrincipal[" + username + "]";
    }
}
