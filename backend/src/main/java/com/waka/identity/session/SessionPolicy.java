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

    /**
     * Founder, 23 November 2026: a login code is asked only on a new phone, after "Se déconnecter", after a
     * reinstall, or after 90 days without opening WAKA; never once a year for a person who keeps using it. Each use
     * pushes the 90 days back; the absolute limit (100 years) only satisfies the mandatory column. Agents of the
     * administration keep their own short limit (AdminPolicy, one day at most).
     */
    public static SessionPolicy mobile() {
        return new SessionPolicy(Duration.ofDays(36_500), Duration.ofDays(90));
    }
}

