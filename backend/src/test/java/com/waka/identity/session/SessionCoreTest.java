package com.waka.identity.session;

import com.waka.identity.otp.OtpCrypto;
import com.waka.identity.otp.PersistedOtpProofVerifier;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.junit.jupiter.api.Assertions.*;

/** Pure local tests: no server, provider, database connection or production policy. */
class SessionCoreTest {
    @Test void policyRequiresExplicitPositiveMicrosecondDurations() {
        assertThrows(IllegalArgumentException.class, () -> new SessionPolicy(null, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new SessionPolicy(Duration.ZERO, Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new SessionPolicy(Duration.ofSeconds(1), Duration.ofSeconds(2)));
        assertThrows(IllegalArgumentException.class, () -> new SessionPolicy(Duration.ofSeconds(1), Duration.ofNanos(1)));
        assertEquals(Duration.ofSeconds(10), new SessionPolicy(Duration.ofSeconds(30), Duration.ofSeconds(10)).idleLifetime());
    }

    @Test void tokenIsOpaqueCanonicalAndHashIsNotToken() {
        SessionCrypto crypto = crypto();
        char[] token = crypto.newToken();
        try {
            assertEquals(43, token.length);
            assertEquals(32, Base64.getUrlDecoder().decode(new String(token)).length);
            String hash = crypto.tokenHash(token);
            assertEquals(64, hash.length());
            assertNotEquals(new String(token), hash);
            assertEquals(hash, crypto.tokenHash(token.clone()));
            assertThrows(SessionException.class, () -> crypto.tokenHash(new char[43]));
            assertThrows(SessionException.class, () -> crypto.tokenHash("A".repeat(42).toCharArray()));
            assertThrows(SessionException.class, () -> crypto.tokenHash(("A".repeat(42) + "B").toCharArray()));
        } finally { Arrays.fill(token, '\0'); }
    }

    @Test void identityKeyIsMandatoryCopiedAndDomainSeparated() {
        assertThrows(IllegalArgumentException.class, () -> new SessionCrypto(new byte[31], new SecureRandom()));
        byte[] key = new byte[32]; new SecureRandom().nextBytes(key);
        SessionCrypto first = new SessionCrypto(key, new SecureRandom());
        SessionCrypto second = new SessionCrypto(key, new SecureRandom());
        Arrays.fill(key, (byte) 0);
        assertEquals(first.gateHash(), second.gateHash());
        assertEquals(first.phoneHash("UNIT_TEST_ONLY"), second.phoneHash("UNIT_TEST_ONLY"));
        assertNotEquals(first.phoneHash("UNIT_TEST_ONLY"), first.contextHash("UNIT_TEST_ONLY"));
        assertNotEquals(first.gateHash(), crypto().gateHash());
        assertEquals("SessionCrypto[REDACTED]", first.toString());
    }

    @Test void grantCopiesErasesAndNeverPrintsItsSecret() {
        char[] input = "A".repeat(43).toCharArray();
        SessionGrant grant = new SessionGrant(UUID.randomUUID(), UUID.randomUUID(), Instant.MAX, Instant.MAX, input);
        input[0] = 'B';
        char[] copy = grant.token();
        assertEquals('A', copy[0]); copy[0] = 'C';
        char[] another = grant.token();
        assertEquals('A', another[0]);
        assertEquals("SessionGrant[REDACTED]", grant.toString());
        grant.close(); grant.close();
        assertThrows(IllegalStateException.class, grant::token);
        Arrays.fill(input, '\0'); Arrays.fill(copy, '\0'); Arrays.fill(another, '\0');
    }

    @Test void refusesMismatchedTransactionDatasourceAndClockBeforeAnyConnection() {
        var first = new DriverManagerDataSource(); var second = new DriverManagerDataSource();
        JdbcTemplate jdbc = new JdbcTemplate(first);
        Clock clock = Clock.systemUTC();
        byte[] mac = new byte[32], enc = new byte[32];
        new SecureRandom().nextBytes(mac); new SecureRandom().nextBytes(enc);
        OtpCrypto otp = new OtpCrypto(mac, enc, new SecureRandom());
        Arrays.fill(mac, (byte) 0); Arrays.fill(enc, (byte) 0);
        var adapter = new PersistedOtpProofVerifier(jdbc, otp, clock);
        var policy = new SessionPolicy(Duration.ofSeconds(30), Duration.ofSeconds(10));
        assertThrows(IllegalArgumentException.class, () -> new PersistentSessionService(jdbc,
                new DataSourceTransactionManager(second), adapter, crypto(), policy, clock));
        assertThrows(IllegalArgumentException.class, () -> new PersistentSessionService(jdbc,
                new DataSourceTransactionManager(first), adapter, crypto(), policy, Clock.fixed(Instant.EPOCH, java.time.ZoneOffset.UTC)));
        assertThrows(com.waka.identity.otp.OtpException.class, () -> adapter.lockAndVerify(null, null, null));
    }

    private SessionCrypto crypto() {
        byte[] key = new byte[32]; new SecureRandom().nextBytes(key);
        try { return new SessionCrypto(key, new SecureRandom()); }
        finally { Arrays.fill(key, (byte) 0); }
    }
}
