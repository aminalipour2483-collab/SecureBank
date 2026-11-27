package com.bank.security;

import com.bank.exception.AccessDeniedException;

/** Authorization check shared by every administrator-only use case. */
public final class AdminGuard {

    private AdminGuard() { }

    /**
     * @return the same principal, for fluent use
     * @throws AccessDeniedException if {@code principal} is null
     */
    public static AdminPrincipal require(AdminPrincipal principal) {
        if (principal == null) {
            throw new AccessDeniedException("Administrator privileges are required for this operation.");
        }
        return principal;
    }
}
