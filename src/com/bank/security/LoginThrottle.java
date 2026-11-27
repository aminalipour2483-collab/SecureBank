package com.bank.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Failure tracker behind administrator login throttling. Pure policy and state: it never sees a
 * password and never calls the authentication service.
 *
 * <p>Two independent limits are applied and the longer remaining lock wins:
 * <ul>
 *   <li><b>Per username</b> &mdash; the first {@code freeAttempts} failures are free; every failure
 *       after that locks the name for {@code baseLockout}, doubling each time up to
 *       {@code maxLockout}. A successful sign-in clears the name. Counters are forgotten after
 *       {@code forgetAfter} without a failure.</li>
 *   <li><b>Global</b> &mdash; {@code globalFailureLimit} failures (any usernames) within
 *       {@code globalWindow} lock all sign-ins for {@code globalLockout}. This is what stops an
 *       attacker from rotating usernames to dodge the per-name limit.</li>
 * </ul>
 *
 * <p><b>Bounded memory.</b> At most {@code maxTrackedKeys} usernames are remembered (stale ones are
 * purged first, then the least recently used), and a key is truncated to {@link #MAX_KEY_LENGTH}
 * characters, so an attacker cannot grow the state by sending unlimited distinct names.
 *
 * <p><b>Limits of this design.</b> State is in memory: restarting the process resets it, and each
 * client process has its own counters. The global lock also locks out the real administrator while
 * an attack is running (availability traded for safety). The numeric defaults are conservative
 * policy choices, not values derived from measurements.
 *
 * <p>Thread-safe; all methods are synchronized on the instance.
 */
public final class LoginThrottle {

    static final int MAX_KEY_LENGTH = 128;

    /** Tunable thresholds. Validated on construction. */
    public record Policy(int freeAttempts, Duration baseLockout, Duration maxLockout, Duration forgetAfter,
                         int globalFailureLimit, Duration globalWindow, Duration globalLockout,
                         int maxTrackedKeys) {
        public Policy {
            if (freeAttempts < 1) throw new IllegalArgumentException("freeAttempts must be >= 1");
            requirePositive(baseLockout, "baseLockout");
            requirePositive(maxLockout, "maxLockout");
            requirePositive(forgetAfter, "forgetAfter");
            requirePositive(globalWindow, "globalWindow");
            requirePositive(globalLockout, "globalLockout");
            if (maxLockout.compareTo(baseLockout) < 0) {
                throw new IllegalArgumentException("maxLockout must be >= baseLockout");
            }
            if (globalFailureLimit < 2) throw new IllegalArgumentException("globalFailureLimit must be >= 2");
            if (maxTrackedKeys < 1) throw new IllegalArgumentException("maxTrackedKeys must be >= 1");
        }

        /** Conservative defaults: 5 free tries, then 30 s doubling to 15 min; 20 failures / 10 min locks all for 5 min. */
        public static Policy defaults() {
            return new Policy(5, Duration.ofSeconds(30), Duration.ofMinutes(15), Duration.ofHours(1),
                    20, Duration.ofMinutes(10), Duration.ofMinutes(5), 1_000);
        }

        private static void requirePositive(Duration d, String name) {
            if (d == null || d.isZero() || d.isNegative()) {
                throw new IllegalArgumentException(name + " must be a positive duration");
            }
        }
    }

    private static final class Entry {
        int failures;
        Instant lastFailure;
        Instant lockedUntil = Instant.MIN;
    }

    private final Policy policy;
    private final Clock clock;
    /** Access-ordered so the eldest entry is the least recently used one. */
    private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>(16, 0.75f, true);
    private final ArrayDeque<Instant> recentFailures = new ArrayDeque<>();
    private Instant globalLockedUntil = Instant.MIN;

    public LoginThrottle(Policy policy, Clock clock) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** @return the time left before an attempt for {@code username} may be checked; {@link Duration#ZERO} if none. */
    public synchronized Duration remainingLockout(String username) {
        Instant now = clock.instant();
        Duration remaining = Duration.ZERO;
        if (globalLockedUntil.isAfter(now)) remaining = Duration.between(now, globalLockedUntil);
        Entry entry = entries.get(normalize(username));
        if (entry != null && entry.lockedUntil.isAfter(now)) {
            Duration own = Duration.between(now, entry.lockedUntil);
            if (own.compareTo(remaining) > 0) remaining = own;
        }
        return remaining;
    }

    public synchronized void recordFailure(String username) {
        Instant now = clock.instant();

        // Global limit: count failures inside the sliding window across all names.
        Instant windowStart = now.minus(policy.globalWindow());
        while (!recentFailures.isEmpty() && recentFailures.peekFirst().isBefore(windowStart)) {
            recentFailures.removeFirst();
        }
        recentFailures.addLast(now);
        if (recentFailures.size() >= policy.globalFailureLimit()) {
            globalLockedUntil = now.plus(policy.globalLockout());
            recentFailures.clear();
        }

        // Per-name limit.
        String key = normalize(username);
        Entry entry = entries.get(key);
        if (entry != null && Duration.between(entry.lastFailure, now).compareTo(policy.forgetAfter()) > 0) {
            entries.remove(key);
            entry = null;
        }
        if (entry == null) {
            makeRoom(now);
            entry = new Entry();
            entries.put(key, entry);
        }
        entry.failures++;
        entry.lastFailure = now;
        if (entry.failures >= policy.freeAttempts()) {
            int doublings = Math.min(entry.failures - policy.freeAttempts(), 20);
            Duration lock = policy.baseLockout().multipliedBy(1L << doublings);
            if (lock.compareTo(policy.maxLockout()) > 0) lock = policy.maxLockout();
            entry.lockedUntil = now.plus(lock);
        }
    }

    /** A correct sign-in clears that name's failure history (the global window is left alone). */
    public synchronized void recordSuccess(String username) {
        entries.remove(normalize(username));
    }

    /** Number of usernames currently remembered (exposed for tests and diagnostics). */
    synchronized int trackedKeys() { return entries.size(); }

    private void makeRoom(Instant now) {
        if (entries.size() < policy.maxTrackedKeys()) return;
        for (Iterator<Map.Entry<String, Entry>> it = entries.entrySet().iterator(); it.hasNext(); ) {
            Entry e = it.next().getValue();
            boolean stale = Duration.between(e.lastFailure, now).compareTo(policy.forgetAfter()) > 0;
            if (stale && !e.lockedUntil.isAfter(now)) it.remove();
        }
        if (entries.size() >= policy.maxTrackedKeys()) {
            Iterator<String> it = entries.keySet().iterator();
            it.next();
            it.remove();                       // least recently used
        }
    }

    static String normalize(String username) {
        String key = username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
        return key.length() > MAX_KEY_LENGTH ? key.substring(0, MAX_KEY_LENGTH) : key;
    }
}
