package com.bank.security;

import com.bank.exception.AccessDeniedException;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/** Thread-safe, single-customer-session context for the desktop client process. */
public final class CustomerSessionContext {
    private static final CustomerSessionContext INSTANCE = new CustomerSessionContext();
    private final AtomicReference<CustomerPrincipal> current = new AtomicReference<>();

    public CustomerSessionContext() { }

    public static CustomerSessionContext getInstance() { return INSTANCE; }

    public void establish(CustomerPrincipal principal) {
        if (principal == null) throw new IllegalArgumentException("principal is required");
        current.set(principal);
    }

    public Optional<CustomerPrincipal> currentPrincipal() {
        return Optional.ofNullable(current.get());
    }

    public CustomerPrincipal requireCurrent(CustomerPrincipal expected) {
        CustomerPrincipal actual = current.get();
        if (expected == null || actual == null || !actual.sessionId().equals(expected.sessionId())) {
            throw new AccessDeniedException("Customer session is missing, expired, or has been replaced.");
        }
        return actual;
    }

    public boolean invalidate(CustomerPrincipal expected) {
        if (expected == null) return false;
        CustomerPrincipal actual = current.get();
        return actual != null
                && actual.sessionId().equals(expected.sessionId())
                && current.compareAndSet(actual, null);
    }

    public void invalidateCurrent() { current.set(null); }
}
