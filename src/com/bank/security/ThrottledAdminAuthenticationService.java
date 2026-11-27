package com.bank.security;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Decorator that adds brute-force throttling to any {@link AdminAuthenticationService}.
 *
 * <p>While a lock is active the delegate is not called at all: the attempt is refused with
 * {@link AdminLockedOutException}, so even the correct password is not accepted and no BCrypt work is
 * spent on it. Failures and successes are reported to the {@link LoginThrottle}.
 *
 * <p>Concurrent attempts that arrive in the same instant can each pass the lock check before any
 * failure is recorded, so the number of attempts in a burst is bounded by the number of calling
 * threads, not by the policy. The Swing login form is single-threaded.
 *
 * <p>Audit events go to the {@code com.bank.audit.admin-login} logger. Passwords are never logged and
 * the user-supplied name is sanitised first (it is attacker-controlled text).
 */
public final class ThrottledAdminAuthenticationService implements AdminAuthenticationService {

    static final String AUDIT_LOGGER = "com.bank.audit.admin-login";
    private static final Logger AUDIT = Logger.getLogger(AUDIT_LOGGER);
    private static final int LOGGED_NAME_LENGTH = 64;

    private final AdminAuthenticationService delegate;
    private final LoginThrottle throttle;

    public ThrottledAdminAuthenticationService(AdminAuthenticationService delegate, LoginThrottle throttle) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.throttle = Objects.requireNonNull(throttle, "throttle");
    }

    /** @throws AdminLockedOutException while sign-in is locked */
    @Override
    public Optional<AdminPrincipal> authenticate(String username, String password) {
        Duration locked = throttle.remainingLockout(username);
        if (!locked.isZero()) {
            AUDIT.warning("Admin sign-in refused while locked (" + describe(locked) + " left) for name \""
                    + printable(username) + "\"");
            throw new AdminLockedOutException(locked);
        }
        Optional<AdminPrincipal> result = delegate.authenticate(username, password);
        if (result.isPresent()) {
            throttle.recordSuccess(username);
            AUDIT.info("Admin sign-in succeeded for \"" + printable(username) + "\"");
        } else {
            throttle.recordFailure(username);
            AUDIT.warning("Admin sign-in failed for name \"" + printable(username) + "\"");
        }
        return result;
    }

    private static String describe(Duration d) { return AdminLockedOutException.describe(d); }

    /** Keeps log lines single-line and bounded: control characters become '?', long names are cut. */
    static String printable(String raw) {
        if (raw == null) return "";
        StringBuilder sb = new StringBuilder(Math.min(raw.length(), LOGGED_NAME_LENGTH));
        for (int i = 0; i < raw.length() && sb.length() < LOGGED_NAME_LENGTH; i++) {
            char c = raw.charAt(i);
            sb.append(Character.isISOControl(c) || c == '"' ? '?' : c);
        }
        return sb.toString();
    }
}
