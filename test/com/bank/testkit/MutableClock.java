package com.bank.testkit;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** Test clock that only moves when told to. */
public final class MutableClock extends Clock {
    private Instant now;

    public MutableClock(Instant start) { this.now = start; }

    public synchronized void advance(Duration d) { now = now.plus(d); }

    @Override public ZoneId getZone() { return ZoneOffset.UTC; }
    @Override public Clock withZone(ZoneId zone) { return this; }
    @Override public synchronized Instant instant() { return now; }
}
