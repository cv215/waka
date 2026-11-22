package com.waka.identity.session;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Founder, 23 November 2026: a code only on a new phone, after logout or reinstall, or after 90 days unused. */
class SessionPolicyMobileTest {
    @Test void ninetyDaysWithoutUseAndNoYearlyLogin() {
        SessionPolicy policy = SessionPolicy.mobile();
        assertEquals(Duration.ofDays(90), policy.idleLifetime());
        assertTrue(policy.absoluteLifetime().compareTo(Duration.ofDays(365 * 50)) > 0, "never asked again once a year");
    }
}
