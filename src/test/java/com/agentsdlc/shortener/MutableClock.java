package com.agentsdlc.shortener;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** A clock tests move by hand, so time-dependent behaviour is asserted without sleeping. */
public final class MutableClock extends Clock {

    private volatile Instant now;

    /**
     * Creates the clock.
     *
     * @param start the initial instant
     */
    public MutableClock(Instant start) {
        this.now = start;
    }

    /**
     * Moves time forward.
     *
     * @param duration how far to advance
     */
    public void advance(Duration duration) {
        now = now.plus(duration);
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return now;
    }
}
