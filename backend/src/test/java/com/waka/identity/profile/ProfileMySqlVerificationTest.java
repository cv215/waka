package com.waka.identity.profile;

import com.waka.identity.otp.OtpCrypto;
import com.waka.identity.otp.OtpPolicy;
import com.waka.identity.otp.PersistentOtpService;
import com.waka.identity.otp.PersistedOtpProofVerifier;
import com.waka.identity.otp.SmsGateway;
import com.waka.identity.otp.SmsMessage;
import com.waka.identity.session.PersistentSessionService;
import com.waka.identity.session.SessionCrypto;
import com.waka.identity.session.SessionException;
import com.waka.identity.session.SessionGrant;
import com.waka.identity.session.SessionPolicy;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HexFormat;
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

/** Real opt-in MySQL/V6; test keys are random and wiped, no real name or contact is used. */
@EnabledIfEnvironmentVariable(named = "WAKA_VERIFY_MYSQL", matches = "true")
class ProfileMySqlVerificationTest {
    private static final Duration REPLAY = Duration.ofSeconds(5);
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager manager;
    private OtpCrypto otpCrypto;
    private SessionCrypto sessionCrypto;
    private byte[] lookupKey, profileEnc, profileMac;
    private MutableClock clock;
    private CapturingGateway gateway;
    private PersistentOtpService otp;
    private PersistentSessionService sessions;
    private PersistentProfileService profiles;
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
        jdbc = new JdbcTemplate(source); jdbc.setQueryTimeout(5);
        manager = new DataSourceTransactionManager(source);
        SecureRandom random = new SecureRandom();
        byte[] mac = new byte[32], enc = new byte[32];
        lookupKey = new byte[32]; profileEnc = new byte[32]; profileMac = new byte[32];
        random.nextBytes(mac); random.nextBytes(enc); random.nextBytes(lookupKey);
        random.nextBytes(profileEnc); random.nextBytes(profileMac);
        otpCrypto = new OtpCrypto(mac, enc, random);
        Arrays.fill(mac, (byte) 0); Arrays.fill(enc, (byte) 0);
        sessionCrypto = new SessionCrypto(lookupKey, random);
        clock = new MutableClock(); gateway = new CapturingGateway();
        otp = new PersistentOtpService(jdbc, manager, otpCrypto, new OtpPolicy(100), gateway, clock);
        sessions = new PersistentSessionService(jdbc, manager, new PersistedOtpProofVerifier(jdbc, otpCrypto, clock),
                sessionCrypto, new SessionPolicy(Duration.ofSeconds(600), Duration.ofSeconds(120)), clock);
        gate = sessions.identityGateHash();
        profiles = instance("test-key");
    }

    @AfterEach void cleanOnlyRandomNamespacesAndRespectForeignKeys() {
        try {
            if (jdbc != null && gate != null) {
                jdbc.update("DELETE FROM identity_profile_command WHERE gate_hash=?", gate);
                jdbc.update("DELETE FROM identity_profile WHERE gate_hash=?", gate);
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
            for (byte[] key : new byte[][] { lookupKey, profileEnc, profileMac }) if (key != null) Arrays.fill(key, (byte) 0);
            if (gateway != null && gateway.code != null) Arrays.fill(gateway.code, '\0');
        }
    }

    @Test void initialReadIsVirtualAndCreatesNoRow() {
        char[] token = token(contact);
        var view = profiles.read(token, client);
        assertEquals(1, view.version()); assertNull(view.displayName()); assertNull(view.updatedAt());
        assertFalse(view.idempotentReplay());
        assertEquals(0, count("identity_profile")); assertEquals(0, count("identity_profile_command"));
    }

    @Test void nameIsEncryptedAndReplayReturnsTheOriginalResultOnly() {
        char[] token = token(contact);
        UUID first = UUID.randomUUID();
        var created = profiles.patch(token, client, first, 1, "  E" + (char) 0x0301 + "lodie ");
        assertEquals(2, created.version()); assertEquals("Élodie", created.displayName());
        assertFalse(created.idempotentReplay());
        byte[] stored = jdbc.queryForObject("SELECT display_cipher FROM identity_profile WHERE gate_hash=?", byte[].class, gate);
        assertFalse(HexFormat.of().formatHex(stored).contains(
                HexFormat.of().formatHex("lodie".getBytes(StandardCharsets.UTF_8))));
        assertEquals("Élodie", profiles.read(token, client).displayName());

        clock.advance(1);
        var cleared = profiles.patch(token, client, UUID.randomUUID(), 2, null);
        assertEquals(3, cleared.version()); assertNull(cleared.displayName());
        var replay = profiles.patch(token, client, first, 1, "Élodie");
        assertTrue(replay.idempotentReplay());
        assertEquals(2, replay.version()); assertEquals("Élodie", replay.displayName());
        assertEquals(created.updatedAt(), replay.updatedAt());
        var current = profiles.read(token, client);
        assertEquals(3, current.version()); assertNull(current.displayName());
        assertEquals(2, count("identity_profile_command"));
    }

    @Test void conflictsAreDistinctAndChangeNothing() {
        char[] token = token(contact);
        UUID key = UUID.randomUUID();
        profiles.patch(token, client, key, 1, "Alpha");
        assertProfileFailure(ProfileException.Code.IDEMPOTENCY_KEY_REUSED, () -> profiles.patch(token, client, key, 1, "Beta"));
        assertProfileFailure(ProfileException.Code.IDEMPOTENCY_KEY_REUSED, () -> profiles.patch(token, client, key, 1, null));
        assertProfileFailure(ProfileException.Code.VERSION_CONFLICT, () -> profiles.patch(token, client, UUID.randomUUID(), 1, "Beta"));
        assertProfileFailure(ProfileException.Code.VERSION_CONFLICT, () -> profiles.patch(token, client, UUID.randomUUID(), 3, "Beta"));
        assertProfileFailure(ProfileException.Code.INVALID_REQUEST, () -> profiles.patch(token, client, UUID.randomUUID(), 2, "A" + (char) 0x202e + "B"));
        var current = profiles.read(token, client);
        assertEquals(2, current.version()); assertEquals("Alpha", current.displayName());
        assertEquals(1, count("identity_profile_command"));
    }

    @Test void expiredReplayIsNeverReappliedAndMaintenanceKeepsMarkers() {
        char[] token = token(contact);
        UUID key = UUID.randomUUID();
        profiles.patch(token, client, key, 1, "Alpha");
        UUID other = UUID.randomUUID();
        profiles.patch(token, client, other, 2, "Beta");
        clock.advance(REPLAY.toSeconds());
        assertProfileFailure(ProfileException.Code.REPLAY_EXPIRED, () -> profiles.patch(token, client, key, 1, "Alpha"));
        assertProfileFailure(ProfileException.Code.REPLAY_EXPIRED, () -> profiles.patch(token, client, key, 1, "Alpha"));
        assertProfileFailure(ProfileException.Code.IDEMPOTENCY_KEY_REUSED, () -> profiles.patch(token, client, key, 3, "Alpha"));
        assertEquals(1, profiles.eraseExpiredResponseSecrets());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM identity_profile_command WHERE gate_hash=? AND response_cipher IS NOT NULL",
                Integer.class, gate));
        assertEquals(2, count("identity_profile_command"));
        assertProfileFailure(ProfileException.Code.REPLAY_EXPIRED, () -> profiles.patch(token, client, other, 2, "Beta"));
        var current = profiles.read(token, client);
        assertEquals(3, current.version()); assertEquals("Beta", current.displayName());
    }

    @Test void accountsAreIsolatedEvenWithTheSameIdempotencyKey() {
        char[] mine = token(contact);
        clock.advance(61);
        char[] theirs = token(otherContact);
        UUID shared = UUID.randomUUID();
        profiles.patch(mine, client, shared, 1, "Alpha");
        var other = profiles.patch(theirs, client, shared, 1, "Beta");
        assertFalse(other.idempotentReplay()); assertEquals("Beta", other.displayName());
        assertEquals("Alpha", profiles.read(mine, client).displayName());
        assertEquals("Beta", profiles.read(theirs, client).displayName());
        assertNotEquals(profiles.read(mine, client).accountId(), profiles.read(theirs, client).accountId());
    }

    @Test void movedCiphertextOrForeignKeyIsUnavailableNotReadable() {
        char[] mine = token(contact);
        clock.advance(61);
        char[] theirs = token(otherContact);
        profiles.patch(mine, client, UUID.randomUUID(), 1, "Alpha");
        profiles.patch(theirs, client, UUID.randomUUID(), 1, "Beta");
        UUID theirAccount = profiles.read(theirs, client).accountId();
        UUID myAccount = profiles.read(mine, client).accountId();
        var moved = jdbc.queryForMap("SELECT display_cipher,updated_at FROM identity_profile WHERE gate_hash=? AND account_id=?",
                gate, theirAccount.toString());
        assertEquals(1, jdbc.update("UPDATE identity_profile SET display_cipher=?,updated_at=? WHERE gate_hash=? AND account_id=?",
                moved.get("display_cipher"), moved.get("updated_at"), gate, myAccount.toString()));
        assertSessionFailure(SessionException.Code.SERVICE_UNAVAILABLE, () -> profiles.read(mine, client));
        assertEquals("Beta", profiles.read(theirs, client).displayName());
        assertSessionFailure(SessionException.Code.SERVICE_UNAVAILABLE, () -> instance("other-key").read(theirs, client));
    }

    @Test void revokedOrForeignContextSessionWritesNothing() {
        char[] token = token(contact);
        assertSessionFailure(SessionException.Code.INVALID_SESSION,
                () -> profiles.patch(token, UUID.randomUUID().toString(), UUID.randomUUID(), 1, "Alpha"));
        sessions.revoke(token, client);
        assertSessionFailure(SessionException.Code.INVALID_SESSION, () -> profiles.read(token, client));
        assertSessionFailure(SessionException.Code.INVALID_SESSION, () -> profiles.patch(token, client, UUID.randomUUID(), 1, "Alpha"));
        assertEquals(0, count("identity_profile")); assertEquals(0, count("identity_profile_command"));
    }

    @Test void raceWithRevocationNeverWritesAfterTheSessionEnds() throws Exception {
        char[] token = token(contact);
        var turn = new java.util.concurrent.atomic.AtomicInteger();
        List<Boolean> results = concurrent(8, () -> {
            if (turn.getAndIncrement() == 3) {
                sessions.revoke(token.clone(), client);
                return Boolean.FALSE;
            }
            try { profiles.patch(token.clone(), client, UUID.randomUUID(), 1, "Alpha"); return Boolean.TRUE; }
            catch (SessionException | ProfileException refused) { return Boolean.FALSE; }
        });
        assertSessionFailure(SessionException.Code.INVALID_SESSION, () -> profiles.read(token, client));
        long written = count("identity_profile");
        assertTrue(written <= 1);
        assertEquals(results.stream().filter(Boolean::booleanValue).count(), written);
        assertEquals(written, count("identity_profile_command"));
        if (written == 1) {
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM identity_profile p JOIN identity_session s ON s.account_id=p.account_id "
                    + "AND s.gate_hash=p.gate_hash WHERE p.gate_hash=? AND p.updated_at<=s.ended_at", Integer.class, gate));
        }
    }

    @Test void concurrentEditsHaveOneEffectAndSameKeyRetriesReplayIt() throws Exception {
        char[] token = token(contact);
        List<String> distinct = concurrent(8, () -> {
            try { profiles.patch(token.clone(), client, UUID.randomUUID(), 1, "Alpha"); return "OK"; }
            catch (ProfileException refused) { return refused.code().name(); }
        });
        assertEquals(1, distinct.stream().filter("OK"::equals).count());
        assertEquals(7, distinct.stream().filter("VERSION_CONFLICT"::equals).count());
        UUID key = UUID.randomUUID();
        List<Boolean> replays = concurrent(8, () -> profiles.patch(token.clone(), client, key, 2, "Beta").idempotentReplay());
        assertEquals(1, replays.stream().filter(replay -> !replay).count());
        var current = profiles.read(token, client);
        assertEquals(3, current.version()); assertEquals("Beta", current.displayName());
        assertEquals(2, count("identity_profile_command"));
    }

    private PersistentProfileService instance(String keyId) {
        return new PersistentProfileService(sessions, jdbc, manager,
                new ProfileCrypto(profileEnc, profileMac, keyId, new SecureRandom()), REPLAY, clock);
    }
    private char[] token(String phone) {
        var challenge = otp.request(client, UUID.randomUUID(), phone);
        otpGate = jdbc.queryForObject("SELECT global_gate FROM otp_challenge WHERE id=?", String.class, challenge.id().toString());
        char[] code = gateway.code.clone();
        PersistentOtpService.Proof proof;
        try { proof = otp.verify(challenge.id(), client, UUID.randomUUID(), code); }
        finally { Arrays.fill(code, '\0'); }
        try (SessionGrant grant = sessions.open(proof, client, phone)) { return grant.token(); }
    }
    private long count(String table) {
        if (!table.equals("identity_profile") && !table.equals("identity_profile_command")) throw new IllegalArgumentException();
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE gate_hash=?", Long.class, gate);
    }
    private static void assertProfileFailure(ProfileException.Code code, Runnable operation) {
        assertEquals(code, assertThrows(ProfileException.class, operation::run).code());
    }
    private static void assertSessionFailure(SessionException.Code code, Runnable operation) {
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
    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> time = new AtomicReference<>(Instant.parse("2026-10-28T10:00:00Z"));
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) throw new IllegalArgumentException(); return this;
        }
        @Override public Instant instant() { return time.get(); }
        void advance(long seconds) { time.updateAndGet(value -> value.plusSeconds(seconds)); }
    }
}
