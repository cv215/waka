package com.waka.identity.session;

import com.waka.identity.otp.OtpCrypto;
import com.waka.identity.otp.OtpPolicy;
import com.waka.identity.otp.PersistentOtpService;
import com.waka.identity.otp.PersistedOtpProofVerifier;
import com.waka.identity.otp.SmsGateway;
import com.waka.identity.otp.SmsMessage;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.junit.jupiter.api.Assertions.*;

/** Real opt-in MySQL/V4; SMS is a test-only capture, never a delivery/provider proof. */
@EnabledIfEnvironmentVariable(named = "WAKA_VERIFY_MYSQL", matches = "true")
class SessionMySqlVerificationTest {
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager manager;
    private OtpCrypto otpCrypto;
    private SessionCrypto crypto;
    private byte[] lookupKey;
    private MutableClock clock;
    private CapturingGateway gateway;
    private PersistentOtpService otp;
    private PersistentSessionService service;
    private String gate;
    private String otpGate;
    private final String client = UUID.randomUUID().toString();
    private final String contact = "+2376" + "0".repeat(8);
    private final String otherContact = "+2376" + "0".repeat(7) + "1";

    @BeforeEach void prepare() {
        String url = required("WAKA_DB_URL");
        if (!url.startsWith("jdbc:mysql://127.0.0.1:3306/waka_test?")
                || !url.contains("sslMode=VERIFY_CA") || url.contains("user=") || url.contains("password=")) {
            throw new IllegalStateException("LOCAL_TEST_DATABASE_REQUIRED");
        }
        DriverManagerDataSource source = new DriverManagerDataSource();
        source.setUrl(url); source.setUsername(required("WAKA_DB_USER")); source.setPassword(required("WAKA_DB_PASSWORD"));
        jdbc = new JdbcTemplate(source); jdbc.setQueryTimeout(3);
        manager = new DataSourceTransactionManager(source);
        SecureRandom random = new SecureRandom();
        byte[] mac = new byte[32], enc = new byte[32];
        lookupKey = new byte[32]; random.nextBytes(mac); random.nextBytes(enc); random.nextBytes(lookupKey);
        otpCrypto = new OtpCrypto(mac, enc, random);
        Arrays.fill(mac, (byte) 0); Arrays.fill(enc, (byte) 0);
        crypto = new SessionCrypto(lookupKey, random);
        gate = crypto.gateHash(); clock = new MutableClock(); gateway = new CapturingGateway();
        otp = new PersistentOtpService(jdbc, manager, otpCrypto, new OtpPolicy(100), gateway, clock);
        service = instance(crypto);
    }

    @AfterEach void cleanOnlyRandomNamespacesAndRespectForeignKeys() {
        try {
            if (jdbc != null && gate != null) {
                jdbc.update("DELETE FROM identity_session WHERE gate_hash=?", gate);
                jdbc.update("DELETE FROM identity_account WHERE gate_hash=?", gate);
                jdbc.update("DELETE FROM identity_session_gate WHERE gate_hash=?", gate);
            }
            if (jdbc != null && otpGate != null) {
                jdbc.update("DELETE FROM otp_verification WHERE global_gate=?", otpGate);
                jdbc.update("DELETE FROM otp_rate_event WHERE global_gate=?", otpGate);
                jdbc.update("DELETE FROM otp_challenge WHERE global_gate=?", otpGate);
                jdbc.update("DELETE FROM otp_gate WHERE gate_hash=?", otpGate);
            }
        } finally {
            if (lookupKey != null) Arrays.fill(lookupKey, (byte) 0);
            if (gateway != null && gateway.code != null) Arrays.fill(gateway.code, '\0');
        }
    }

    @Test void opensFromRealCoreProofAndStoresOnlyHashAndOneAccount() {
        PersistentOtpService.Proof proof = proof(contact);
        try (SessionGrant grant = service.open(proof, client, contact)) {
            char[] token = grant.token();
            try {
                assertEquals(grant.accountId(), instance(crypto).authenticate(token, client).accountId());
                assertEquals(crypto.tokenHash(token), jdbc.queryForObject(
                        "SELECT token_hash FROM identity_session WHERE gate_hash=?", String.class, gate));
                assertEquals(1, count("identity_account")); assertEquals(1, count("identity_session"));
                assertFailure(SessionException.Code.PROOF_ALREADY_USED, () -> service.open(proof, client, contact));
            } finally { Arrays.fill(token, '\0'); }
        }
        clock.advance(61);
        try (SessionGrant next = service.open(proof(contact), client, contact)) {
            assertEquals(1, count("identity_account")); assertEquals(2, count("identity_session"));
        }
    }

    @Test void concurrentProofUseHasExactlyOneSessionWithoutReturningTokenOnReplay() throws Exception {
        var proof = proof(contact);
        List<Boolean> results = concurrent(12, () -> {
            try (SessionGrant grant = instance(crypto).open(proof, client, contact)) { return Boolean.TRUE; }
            catch (SessionException failure) {
                assertEquals(SessionException.Code.PROOF_ALREADY_USED, failure.code());
                return Boolean.FALSE;
            }
        });
        assertEquals(1, results.stream().filter(Boolean::booleanValue).count());
        assertEquals(1, count("identity_session")); assertEquals(1, count("identity_account"));
    }

    @Test void rejectsForgedWrongContextAndWrongPhoneWithoutIssuingAnything() {
        var proof = proof(contact);
        assertFailure(SessionException.Code.INVALID_PROOF, () -> service.open(
                new PersistentOtpService.Proof(proof.challengeId(), UUID.randomUUID(), proof.expiresAt()), client, contact));
        assertFailure(SessionException.Code.INVALID_PROOF, () -> service.open(proof, UUID.randomUUID().toString(), contact));
        assertFailure(SessionException.Code.INVALID_PROOF, () -> service.open(proof, client, otherContact));
        assertEquals(0, count("identity_account")); assertEquals(0, count("identity_session"));
        try (SessionGrant grant = service.open(proof, client, contact)) { assertEquals(1, count("identity_session")); }
    }

    @Test void databaseExpiryWinsOverForgedFutureDtoExpiry() {
        var proof = proof(contact); clock.advance(300);
        var forgedExpiry = new PersistentOtpService.Proof(proof.challengeId(), proof.proofId(), clock.instant().plusSeconds(1000));
        assertFailure(SessionException.Code.INVALID_PROOF, () -> service.open(forgedExpiry, client, contact));
        assertEquals(0, count("identity_session"));
    }

    @Test void consumedFlagDoesNotOverrideSendingOrRejectedSubmission() {
        var proof = proof(contact);
        for (String submission : List.of("SENDING", "REJECTED")) {
            jdbc.update("UPDATE otp_challenge SET submission=? WHERE id=? AND global_gate=?",
                    submission, proof.challengeId().toString(), otpGate);
            assertFailure(SessionException.Code.INVALID_PROOF, () -> service.open(proof, client, contact));
        }
        assertEquals(0, count("identity_account")); assertEquals(0, count("identity_session"));
        jdbc.update("UPDATE otp_challenge SET submission='UNKNOWN' WHERE id=? AND global_gate=?",
                proof.challengeId().toString(), otpGate);
        try (SessionGrant grant = service.open(proof, client, contact)) { assertEquals(1, count("identity_session")); }
    }

    @Test void idleDeadlineExpiresAtBoundaryAndSurvivesRestartAndClockRollback() {
        try (SessionGrant grant = service.open(proof(contact), client, contact)) {
            char[] token = grant.token();
            try {
                clock.advance(9);
                assertEquals(grant.sessionId(), service.authenticate(token, client).sessionId());
                clock.advance(10);
                assertFailure(SessionException.Code.INVALID_SESSION, () -> instance(crypto).authenticate(token, client));
                assertEquals("EXPIRED", jdbc.queryForObject(
                        "SELECT end_reason FROM identity_session WHERE gate_hash=?", String.class, gate));
                clock.advance(-1);
                assertFailure(SessionException.Code.SERVICE_UNAVAILABLE, () -> service.authenticate(token, client));
            } finally { Arrays.fill(token, '\0'); }
        }
    }

    @Test void absoluteDeadlineCannotBeExtendedByActivity() {
        try (SessionGrant grant = service.open(proof(contact), client, contact)) {
            char[] token = grant.token();
            try {
                clock.advance(9); service.authenticate(token, client);
                clock.advance(9); service.authenticate(token, client);
                clock.advance(9);
                assertEquals(grant.absoluteExpiresAt(), service.authenticate(token, client).idleExpiresAt());
                clock.advance(3);
                assertFailure(SessionException.Code.INVALID_SESSION, () -> service.authenticate(token, client));
            } finally { Arrays.fill(token, '\0'); }
        }
    }

    @Test void foreignContextCannotRevokeAndConcurrentLogoutIsDurable() throws Exception {
        try (SessionGrant grant = service.open(proof(contact), client, contact)) {
            char[] token = grant.token();
            try {
                assertFailure(SessionException.Code.INVALID_SESSION,
                        () -> service.revoke(token, UUID.randomUUID().toString()));
                assertFailure(SessionException.Code.INVALID_SESSION,
                        () -> service.authenticate(token, UUID.randomUUID().toString()));
                service.authenticate(token, client);
                concurrent(8, () -> { instance(crypto).revoke(token, client); return Boolean.TRUE; });
                assertFailure(SessionException.Code.INVALID_SESSION, () -> instance(crypto).authenticate(token, client));
                service.revoke(token, client);
                assertEquals("REVOKED", jdbc.queryForObject(
                        "SELECT end_reason FROM identity_session WHERE gate_hash=?", String.class, gate));
            } finally { Arrays.fill(token, '\0'); }
        }
    }

    @Test void databaseFailureRollsBackAccountAndProofUseTogether() {
        SessionCrypto repeated = new SessionCrypto(lookupKey, new RepeatingRandom());
        var stableTokenService = instance(repeated);
        try (SessionGrant first = stableTokenService.open(proof(contact), client, contact)) {
            var second = proof(otherContact);
            assertFailure(SessionException.Code.SERVICE_UNAVAILABLE, () -> stableTokenService.open(second, client, otherContact));
            assertEquals(1, count("identity_account")); assertEquals(1, count("identity_session"));
            try (SessionGrant retry = service.open(second, client, otherContact)) {
                assertEquals(2, count("identity_account")); assertEquals(2, count("identity_session"));
            }
        }
    }

    @Test void disabledAccountCannotAuthenticateOrGainAnotherSession() {
        try (SessionGrant grant = service.open(proof(contact), client, contact)) {
            char[] token = grant.token();
            try {
                jdbc.update("UPDATE identity_account SET status='DISABLED' WHERE gate_hash=?", gate);
                assertFailure(SessionException.Code.INVALID_SESSION, () -> service.authenticate(token, client));
                clock.advance(61);
                var next = proof(contact);
                assertFailure(SessionException.Code.INVALID_SESSION, () -> service.open(next, client, contact));
                assertEquals(1, count("identity_session"));
            } finally { Arrays.fill(token, '\0'); }
        }
    }

    private PersistentSessionService instance(SessionCrypto sessionCrypto) {
        return new PersistentSessionService(jdbc, manager, new PersistedOtpProofVerifier(jdbc, otpCrypto, clock),
                sessionCrypto, new SessionPolicy(Duration.ofSeconds(30), Duration.ofSeconds(10)), clock);
    }
    private PersistentOtpService.Proof proof(String phone) {
        var challenge = otp.request(client, UUID.randomUUID(), phone);
        otpGate = jdbc.queryForObject("SELECT global_gate FROM otp_challenge WHERE id=?", String.class, challenge.id().toString());
        char[] code = gateway.code.clone();
        try { return otp.verify(challenge.id(), client, UUID.randomUUID(), code); }
        finally { Arrays.fill(code, '\0'); }
    }
    private int count(String table) {
        if (!table.equals("identity_account") && !table.equals("identity_session")) throw new IllegalArgumentException();
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE gate_hash=?", Integer.class, gate);
    }
    private static void assertFailure(SessionException.Code code, Runnable operation) {
        assertEquals(code, assertThrows(SessionException.class, operation::run).code());
    }
    private static <T> List<T> concurrent(int count, Callable<T> work) throws Exception {
        try (var executor = Executors.newFixedThreadPool(count)) {
            var tasks = java.util.stream.IntStream.range(0, count).mapToObj(i -> work).toList();
            return executor.invokeAll(tasks).stream().map(future -> {
                try { return future.get(); } catch (Exception failure) { throw new AssertionError("CONCURRENT_TEST_FAILURE", failure); }
            }).toList();
        }
    }
    private static String required(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) throw new IllegalStateException("LOCAL_TEST_CONFIGURATION_REQUIRED");
        return value;
    }
    private static final class CapturingGateway implements SmsGateway {
        private char[] code;
        @Override public Submission send(SmsMessage message) {
            if (code != null) Arrays.fill(code, '\0');
            code = message.code(); return Submission.ACCEPTED;
        }
    }
    private static final class RepeatingRandom extends SecureRandom {
        @Override public void nextBytes(byte[] bytes) { Arrays.fill(bytes, (byte) 7); }
    }
    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> time = new AtomicReference<>(Instant.parse("2026-10-17T10:00:00Z"));
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) throw new IllegalArgumentException(); return this;
        }
        @Override public Instant instant() { return time.get(); }
        void advance(long seconds) { time.updateAndGet(value -> value.plusSeconds(seconds)); }
    }
}

