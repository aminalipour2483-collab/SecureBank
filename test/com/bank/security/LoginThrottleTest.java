package com.bank.security;

import com.bank.exception.AccessDeniedException;
import com.bank.testkit.MutableClock;
import com.bank.util.BCryptUtil;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static com.bank.testkit.TestKit.*;

public class LoginThrottleTest {

    static final Instant T0 = Instant.parse("2026-03-01T10:00:00Z");
    static final LoginThrottle.Policy P = LoginThrottle.Policy.defaults(); // 5 free, 30s..15min, global 20/10min -> 5min

    static LoginThrottle throttle(MutableClock clock) { return new LoginThrottle(P, clock); }

    static void fail(LoginThrottle t, String name, int times) { for (int i = 0; i < times; i++) t.recordFailure(name); }

    public static void main(String[] args) throws Exception {
        perNameLock();
        exponentialBackoff();
        successAndForgetting();
        isolationAndNormalisation();
        globalLimit();
        boundedMemory();
        policyValidation();
        decorator();
        auditLogSanitising();
        concurrency();
        finish("LoginThrottleTest");
    }

    static void perNameLock() {
        section("[1] Per-username lock");
        MutableClock clock = new MutableClock(T0);
        LoginThrottle t = throttle(clock);
        fail(t, "admin", 4);
        check("4 failures: not locked (5 are free)", t.remainingLockout("admin").isZero());
        t.recordFailure("admin");
        checkEquals("5th failure locks for the base 30 s", Duration.ofSeconds(30), t.remainingLockout("admin"));
        clock.advance(Duration.ofSeconds(29));
        check("still locked at 29 s", !t.remainingLockout("admin").isZero());
        clock.advance(Duration.ofSeconds(1));
        check("unlocked at 30 s", t.remainingLockout("admin").isZero());
    }

    static void exponentialBackoff() {
        section("[2] Exponential back-off, capped");
        MutableClock clock = new MutableClock(T0);
        LoginThrottle t = throttle(clock);
        fail(t, "admin", 5);
        Duration[] expected = {Duration.ofSeconds(30), Duration.ofSeconds(60), Duration.ofSeconds(120), Duration.ofSeconds(240)};
        checkEquals("lock 1 = 30 s", expected[0], t.remainingLockout("admin"));
        for (int i = 1; i < expected.length; i++) {
            clock.advance(expected[i - 1]);
            t.recordFailure("admin");
            checkEquals("lock " + (i + 1) + " = " + expected[i].getSeconds() + " s", expected[i], t.remainingLockout("admin"));
        }
        // keep failing right after each expiry until the cap is reached
        for (int i = 0; i < 10; i++) {
            clock.advance(t.remainingLockout("admin"));
            t.recordFailure("admin");
        }
        checkEquals("lock never exceeds the 15-minute cap", P.maxLockout(), t.remainingLockout("admin"));
    }

    static void successAndForgetting() {
        section("[3] Success clears history; stale history is forgotten");
        MutableClock clock = new MutableClock(T0);
        LoginThrottle t = throttle(clock);
        fail(t, "admin", 4);
        t.recordSuccess("admin");
        fail(t, "admin", 4);
        check("after a success the count restarts (4 more failures not locked)", t.remainingLockout("admin").isZero());

        MutableClock clock2 = new MutableClock(T0);
        LoginThrottle t2 = throttle(clock2);
        fail(t2, "admin", 4);
        clock2.advance(P.forgetAfter().plusSeconds(1));
        t2.recordFailure("admin");               // would be the 5th, but the first four were forgotten
        check("failures older than forgetAfter no longer count", t2.remainingLockout("admin").isZero());
        checkEquals("only the new failure is tracked", 1, t2.trackedKeys());
    }

    static void isolationAndNormalisation() {
        section("[4] Names are isolated, but spelling variants share a counter");
        MutableClock clock = new MutableClock(T0);
        LoginThrottle t = throttle(clock);
        fail(t, "alice", 5);
        check("alice locked", !t.remainingLockout("alice").isZero());
        check("bob unaffected", t.remainingLockout("bob").isZero());
        check("case/whitespace variant is the same key", !t.remainingLockout("  ALICE ").isZero());

        LoginThrottle t2 = throttle(new MutableClock(T0));
        t2.recordFailure("Admin"); t2.recordFailure(" admin "); t2.recordFailure("ADMIN");
        t2.recordFailure("admin\t"); t2.recordFailure("aDmIn");
        check("variants cannot be used to get extra free attempts", !t2.remainingLockout("admin").isZero());
        check("null name is tolerated", t2.remainingLockout(null).isZero());
        t2.recordFailure(null);
    }

    static void globalLimit() {
        section("[5] Global limit stops username rotation");
        MutableClock clock = new MutableClock(T0);
        LoginThrottle t = throttle(clock);
        for (int i = 0; i < 19; i++) t.recordFailure("user" + i);
        check("19 distinct-name failures: nobody locked", t.remainingLockout("fresh").isZero());
        t.recordFailure("user19");
        checkEquals("20th failure locks everyone for 5 min", Duration.ofMinutes(5), t.remainingLockout("fresh"));
        check("including a name never seen before and the real admin", !t.remainingLockout("admin").isZero());
        clock.advance(Duration.ofMinutes(5));
        check("global lock expires", t.remainingLockout("fresh").isZero());

        MutableClock clock2 = new MutableClock(T0);
        LoginThrottle t2 = throttle(clock2);
        for (int i = 0; i < 19; i++) { t2.recordFailure("u" + i); clock2.advance(Duration.ofMinutes(1)); }
        check("slow failures spread beyond the 10-min window never trip it", t2.remainingLockout("fresh").isZero());
    }

    static void boundedMemory() {
        section("[6] Memory is bounded");
        LoginThrottle.Policy small = new LoginThrottle.Policy(5, Duration.ofSeconds(30), Duration.ofMinutes(15),
                Duration.ofHours(1), 1_000_000, Duration.ofMinutes(10), Duration.ofMinutes(5), 10);
        MutableClock clock = new MutableClock(T0);
        LoginThrottle t = new LoginThrottle(small, clock);
        for (int i = 0; i < 5_000; i++) t.recordFailure("name-" + i);
        check("5000 distinct names, tracked <= 10 (was " + t.trackedKeys() + ")", t.trackedKeys() <= 10);

        LoginThrottle t2 = new LoginThrottle(small, clock);
        t2.recordFailure("x".repeat(100_000));
        check("very long names are truncated to the same key", !(t2.remainingLockout("x".repeat(100_000)).isNegative()));
        fail(t2, "x".repeat(100_000), 4);
        check("long name still locks after 5 failures", !t2.remainingLockout("x".repeat(500)).isZero());
    }

    static void policyValidation() {
        section("[7] Policy validation");
        checkThrows("freeAttempts < 1", IllegalArgumentException.class, () ->
                new LoginThrottle.Policy(0, Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofHours(1), 5, Duration.ofMinutes(1), Duration.ofMinutes(1), 10));
        checkThrows("maxLockout < baseLockout", IllegalArgumentException.class, () ->
                new LoginThrottle.Policy(3, Duration.ofSeconds(10), Duration.ofSeconds(2), Duration.ofHours(1), 5, Duration.ofMinutes(1), Duration.ofMinutes(1), 10));
        checkThrows("zero duration", IllegalArgumentException.class, () ->
                new LoginThrottle.Policy(3, Duration.ZERO, Duration.ofSeconds(2), Duration.ofHours(1), 5, Duration.ofMinutes(1), Duration.ofMinutes(1), 10));
        checkThrows("globalFailureLimit < 2", IllegalArgumentException.class, () ->
                new LoginThrottle.Policy(3, Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofHours(1), 1, Duration.ofMinutes(1), Duration.ofMinutes(1), 10));
        checkThrows("maxTrackedKeys < 1", IllegalArgumentException.class, () ->
                new LoginThrottle.Policy(3, Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofHours(1), 5, Duration.ofMinutes(1), Duration.ofMinutes(1), 0));
        check("defaults are valid", LoginThrottle.Policy.defaults() != null);
    }

    /** Counts calls and accepts exactly one password. */
    static final class CountingAuth implements AdminAuthenticationService {
        int calls;
        final String goodUser, goodPassword;
        CountingAuth(String u, String p) { goodUser = u; goodPassword = p; }
        @Override public Optional<AdminPrincipal> authenticate(String u, String p) {
            calls++;
            return goodUser.equals(u) && goodPassword.equals(p)
                    ? Optional.of(new AdminPrincipal(u, T0)) : Optional.empty();
        }
    }

    static void decorator() {
        section("[8] Throttled authentication service");
        MutableClock clock = new MutableClock(T0);
        CountingAuth inner = new CountingAuth("admin", "right");
        ThrottledAdminAuthenticationService svc = new ThrottledAdminAuthenticationService(inner, throttle(clock));

        check("correct login before any failure succeeds", svc.authenticate("admin", "right").isPresent());
        for (int i = 0; i < 5; i++) check("wrong password " + (i + 1) + " rejected", svc.authenticate("admin", "bad" + i).isEmpty());
        int callsBefore = inner.calls;
        try {
            svc.authenticate("admin", "right");
            check("CORRECT password refused while locked", false, "no exception");
        } catch (AdminLockedOutException e) {
            check("CORRECT password refused while locked", true);
            checkEquals("retryAfter is 30 s", Duration.ofSeconds(30), e.retryAfter());
            check("message mentions the wait", e.getMessage().contains("30 seconds"), e.getMessage());
            check("is an AccessDeniedException", e instanceof AccessDeniedException);
        }
        checkEquals("delegate (BCrypt) not called while locked", callsBefore, inner.calls);
        checkThrows("locked even for an unknown username (no enumeration)", AdminLockedOutException.class,
                () -> svc.authenticate("admin", "x"));
        checkEquals("locked attempts are not counted as failures (lock stays 30 s)", Duration.ofSeconds(30),
                ((AdminLockedOutException) catchLocked(svc)).retryAfter());

        clock.advance(Duration.ofSeconds(30));
        check("after the lock expires the correct password works", svc.authenticate("admin", "right").isPresent());
        for (int i = 0; i < 4; i++) svc.authenticate("admin", "bad");
        check("success cleared history: 4 more failures are free", svc.authenticate("admin", "right").isPresent());

        checkEquals("message rounds up to minutes", "2 minutes", AdminLockedOutException.describe(Duration.ofSeconds(61)));
        checkEquals("sub-second waits show 1 second", "1 second", AdminLockedOutException.describe(Duration.ofMillis(10)));

        // Real credential checker: wrong passwords really lock, right one still verifies afterwards.
        ConfiguredAdminAuthenticationService real = ConfiguredAdminAuthenticationService.fromValues(
                "root", BCryptUtil.hash("S3cret!pw"), clock);
        ThrottledAdminAuthenticationService svc2 = new ThrottledAdminAuthenticationService(real, throttle(clock));
        for (int i = 0; i < 5; i++) svc2.authenticate("root", "guess" + i);
        checkThrows("real service: locked after 5 wrong passwords", AdminLockedOutException.class,
                () -> svc2.authenticate("root", "S3cret!pw"));
        clock.advance(Duration.ofSeconds(31));
        check("real service: correct password accepted after lock", svc2.authenticate("root", "S3cret!pw").isPresent());
    }

    static Exception catchLocked(AdminAuthenticationService svc) {
        try { svc.authenticate("admin", "x"); } catch (AdminLockedOutException e) { return e; }
        return new IllegalStateException("not locked");
    }

    static void auditLogSanitising() {
        section("[9] Audit log lines cannot be forged");
        Logger log = Logger.getLogger(ThrottledAdminAuthenticationService.AUDIT_LOGGER);
        List<String> lines = new ArrayList<>();
        Handler h = new Handler() {
            @Override public void publish(LogRecord r) { lines.add(r.getMessage()); }
            @Override public void flush() { }
            @Override public void close() { }
        };
        boolean parent = log.getUseParentHandlers();
        log.setUseParentHandlers(false);
        log.addHandler(h);
        try {
            ThrottledAdminAuthenticationService svc = new ThrottledAdminAuthenticationService(
                    new CountingAuth("admin", "right"), throttle(new MutableClock(T0)));
            svc.authenticate("evil\nINFO: Admin sign-in succeeded for \"admin\"\r", "pw-SECRET-123");
            svc.authenticate("n".repeat(10_000), "pw-SECRET-123");
            check("two events logged", lines.size() == 2, lines.toString());
            check("no line breaks in log messages", lines.stream().noneMatch(l -> l.contains("\n") || l.contains("\r")));
            check("long names are truncated", lines.stream().allMatch(l -> l.length() < 200));
            check("passwords never logged", lines.stream().noneMatch(l -> l.contains("SECRET")));
        } finally {
            log.removeHandler(h);
            log.setUseParentHandlers(parent);
        }
    }

    static void concurrency() throws Exception {
        section("[10] Concurrent use");
        MutableClock clock = new MutableClock(T0);
        LoginThrottle t = throttle(clock);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<java.util.concurrent.Future<?>> fs = new ArrayList<>();
        for (int w = 0; w < 8; w++) {
            final int id = w;
            fs.add(pool.submit(() -> {
                for (int i = 0; i < 500; i++) { t.recordFailure("n" + (id * 500 + i) % 50); t.remainingLockout("n" + i); }
            }));
        }
        boolean ok = true;
        for (var f : fs) { try { f.get(30, TimeUnit.SECONDS); } catch (Exception e) { ok = false; } }
        pool.shutdownNow();
        check("8 threads x 500 failures: no exception or deadlock", ok);
        check("tracked keys bounded (" + t.trackedKeys() + " <= 50)", t.trackedKeys() <= 50);
        check("global lock engaged", !t.remainingLockout("anyone").isZero());
    }
}
