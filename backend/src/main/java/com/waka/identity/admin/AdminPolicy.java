package com.waka.identity.admin;

import java.time.Duration;

/** Mandatory, explicitly injected security settings; no production default is assumed here. */
public record AdminPolicy(Duration idleLifetime, Duration absoluteLifetime, int maxFailures,
        Duration failureWindow, Duration lockDuration) {
    public AdminPolicy {
        for (Duration value : new Duration[] { idleLifetime, absoluteLifetime, failureWindow, lockDuration }) {
            if (value == null || value.isNegative() || value.isZero() || value.getNano() % 1000 != 0
                    || value.compareTo(Duration.ofDays(1)) > 0) throw new IllegalArgumentException("INVALID_ADMIN_POLICY");
        }
        if (idleLifetime.compareTo(absoluteLifetime) > 0 || maxFailures < 1 || maxFailures > 20) {
            throw new IllegalArgumentException("INVALID_ADMIN_POLICY");
        }
    }
}
