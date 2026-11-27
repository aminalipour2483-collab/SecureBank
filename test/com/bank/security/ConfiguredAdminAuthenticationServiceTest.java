package com.bank.security;

import com.bank.exception.AccessDeniedException;
import com.bank.util.BCryptUtil;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static com.bank.testkit.TestKit.*;

/** Lives in com.bank.security to exercise the package-private environment factory. */
public class ConfiguredAdminAuthenticationServiceTest {

    public static void main(String[] args) {
        Clock clock = Clock.fixed(Instant.parse("2026-06-01T10:00:00Z"), ZoneOffset.UTC);
        String hash = BCryptUtil.hash("S3cret!");
        ConfiguredAdminAuthenticationService svc = new ConfiguredAdminAuthenticationService("alice", hash, clock);

        section("[1] Authentication");
        Optional<AdminPrincipal> ok = svc.authenticate("alice", "S3cret!");
        check("correct credentials → principal", ok.isPresent());
        checkEquals("principal carries the username", "alice", ok.orElseThrow().username());
        checkEquals("principal timestamp from injected clock", clock.instant(), ok.orElseThrow().authenticatedAt());
        check("surrounding whitespace in username tolerated", svc.authenticate("  alice ", "S3cret!").isPresent());
        check("wrong password rejected", svc.authenticate("alice", "wrong").isEmpty());
        check("wrong username rejected", svc.authenticate("bob", "S3cret!").isEmpty());
        check("username is case-sensitive", svc.authenticate("ALICE", "S3cret!").isEmpty());
        check("null username rejected", svc.authenticate(null, "S3cret!").isEmpty());
        check("null password rejected", svc.authenticate("alice", null).isEmpty());
        check("empty password rejected", svc.authenticate("alice", "").isEmpty());
        check("principal toString never includes a secret", !ok.orElseThrow().toString().contains("S3cret"));

        section("[2] Authorization guard");
        check("guard passes a real principal through", AdminGuard.require(ok.orElseThrow()) == ok.orElseThrow());
        checkThrows("guard rejects null", AccessDeniedException.class, () -> AdminGuard.require(null));

        section("[3] Configuration");
        checkThrows("blank username rejected", IllegalArgumentException.class,
                () -> new ConfiguredAdminAuthenticationService(" ", hash, clock));
        checkThrows("non-BCrypt hash rejected", IllegalArgumentException.class,
                () -> new ConfiguredAdminAuthenticationService("alice", "plaintext", clock));
        checkThrows("only username configured → fail closed", IllegalStateException.class,
                () -> ConfiguredAdminAuthenticationService.fromValues("alice", null, clock));
        checkThrows("only hash configured → fail closed", IllegalStateException.class,
                () -> ConfiguredAdminAuthenticationService.fromValues("", hash, clock));
        ConfiguredAdminAuthenticationService fromEnv = ConfiguredAdminAuthenticationService.fromValues(" bob ", hash, clock);
        check("explicit configuration honoured (username trimmed)", fromEnv.authenticate("bob", "S3cret!").isPresent());
        check("explicit configuration disables the demo account",
                fromEnv.authenticate("admin", "admin123").isEmpty());
        checkThrows("no configuration → no built-in administrator account", IllegalStateException.class,
                () -> ConfiguredAdminAuthenticationService.fromValues(null, null, clock));

        finish("ConfiguredAdminAuthenticationServiceTest");
    }
}
