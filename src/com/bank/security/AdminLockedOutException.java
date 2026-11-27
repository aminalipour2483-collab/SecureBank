package com.bank.security;

import com.bank.exception.AccessDeniedException;

import java.time.Duration;

/**
 * Thrown by the throttled authentication service when sign-in is temporarily refused because of
 * too many recent failures. The attempt was NOT checked against the credentials.
 *
 * <p>The lock applies identically to every username, so receiving this exception does not reveal
 * whether the supplied username exists.
 */
public final class AdminLockedOutException extends AccessDeniedException {

    private final Duration retryAfter;

    public AdminLockedOutException(Duration retryAfter) {
        super("Too many failed sign-in attempts. Try again in " + describe(retryAfter) + ".");
        this.retryAfter = retryAfter;
    }

    public Duration retryAfter() { return retryAfter; }

    /** Rounds up so the user is never told to retry before the lock has actually expired. */
    static String describe(Duration d) {
        long seconds = Math.max(1, (d.toMillis() + 999) / 1000);
        if (seconds < 60) return seconds + (seconds == 1 ? " second" : " seconds");
        long minutes = (seconds + 59) / 60;
        return minutes + (minutes == 1 ? " minute" : " minutes");
    }
}
