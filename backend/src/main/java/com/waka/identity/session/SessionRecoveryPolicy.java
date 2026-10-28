package com.waka.identity.session;

import java.time.Duration;

/** Explicit maximum recovery window; actual expiry is also capped by proof and session. */
public record SessionRecoveryPolicy(Duration lifetime) {
    public SessionRecoveryPolicy {
        try {
            if (lifetime == null || lifetime.isNegative() || lifetime.isZero()
                    || lifetime.getNano() % 1000 != 0 || lifetime.toNanos() < 1000) {
                throw new IllegalArgumentException("INVALID_SESSION_RECOVERY_POLICY");
            }
        } catch (ArithmeticException invalid) { throw new IllegalArgumentException("INVALID_SESSION_RECOVERY_POLICY"); }
    }
}

