package com.bank.testkit;

import com.bank.security.AdminPrincipal;
import com.bank.security.ConfiguredAdminAuthenticationService;
import com.bank.util.BCryptUtil;

import java.time.Clock;

/** An authenticated administrator for tests, obtained the same way production code obtains one. */
public final class TestAdmin {
    public static final String USERNAME = "test-admin";
    private static AdminPrincipal cached;

    private TestAdmin() { }

    public static synchronized AdminPrincipal principal() {
        if (cached == null) {
            cached = new ConfiguredAdminAuthenticationService(USERNAME, BCryptUtil.hash("test-pw"), Clock.systemUTC())
                    .authenticate(USERNAME, "test-pw").orElseThrow();
        }
        return cached;
    }
}
