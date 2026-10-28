package com.waka.identity.session;

import java.time.Duration;

/** Both durations are mandatory security settings, not production defaults. */
public record SessionPolicy(Duration absoluteLifetime, Duration idleLifetime) {
    public SessionPolicy {
        try {
            if (absoluteLifetime == null || idleLifetime == null || absoluteLifetime.isNegative()
                    || idleLifetime.isNegative() || absoluteLifetime.isZero() || idleLifetime.isZero()
                    || idleLifetime.compareTo(absoluteLifetime) > 0
                    || absoluteLifetime.getNano() % 1000 != 0 || idleLifetime.getNano() % 1000 != 0
                    || absoluteLifetime.toNanos() < 1000 || idleLifetime.toNanos() < 1000) {
                throw new IllegalArgumentException("INVALID_SESSION_POLICY");
            }
        } catch (ArithmeticException invalid) { throw new IllegalArgumentException("INVALID_SESSION_POLICY"); }
    }
    long idleMicros() { return idleLifetime.toNanos() / 1000; }
}

