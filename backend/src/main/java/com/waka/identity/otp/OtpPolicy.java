package com.waka.identity.otp;

import java.time.Duration;

/** Initial security policy; the hourly ceiling is mandatory and zero forbids sending. */
public record OtpPolicy(int hourlyBudget) {
    public OtpPolicy {
        if (hourlyBudget < 0 || hourlyBudget > 10_000) throw new IllegalArgumentException("INVALID_OTP_POLICY");
    }
    public Duration lifetime() { return Duration.ofMinutes(5); }
    public Duration cooldown() { return Duration.ofSeconds(60); }
    public Duration rateWindow() { return Duration.ofMinutes(15); }
    public int maximumWrongAttempts() { return 5; }
    public int destinationRequests() { return 3; }
    public int contextRequests() { return 5; }
    public int destinationFailures() { return 10; }
}
