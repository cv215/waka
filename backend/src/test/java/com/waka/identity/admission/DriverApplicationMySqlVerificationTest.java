package com.waka.identity.admission;

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

/** Real opt-in MySQL/V7; synthetic vehicle values only, random keys wiped after each test. */
@EnabledIfEnvironmentVariable(named = "WAKA_VERIFY_MYSQL", matches = "true")
class DriverApplicationMySqlVerificationTest {
    private static final Duration REPLAY = Duration.ofSeconds(5);
    private static final VehicleFields CAR = new VehicleFields("Marque", "Modele", 2020, "Bleu", " ab 12-cd ", 5, 4, "holder");
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager manager;
    private OtpCrypto otpCrypto;
    private byte[] lookupKey, enc, mac;
    private MutableClock clock;
    private CapturingGateway gateway;
    private PersistentOtpService otp;
    private PersistentSessionService sessions;
    private PersistentDriverApplicationService applications;
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
        byte[] otpMac = new byte[32], otpEnc = new byte[32];
        lookupKey = new byte[32]; enc = new byte[32]; mac = new byte[32];
        for (byte[] key : new byte[][] { otpMac, otpEnc, lookupKey, enc, mac }) random.nextBytes(key);
        otpCrypto = new OtpCrypto(otpMac, otpEnc, random);
        Arrays.fill(otpMac, (byte) 0); Arrays.fill(otpEnc, (byte) 0);
        clock = new MutableClock(); gateway = new CapturingGateway();
        otp = new PersistentOtpService(jdbc, manager, otpCrypto, new OtpPolicy(100), gateway, clock);
        sessions = new PersistentSessionService(jdbc, manager, new PersistedOtpProofVerifier(jdbc, otpCrypto, clock),
                new SessionCrypto(lookupKey, random), new SessionPolicy(Duration.ofSeconds(600), Duration.ofSeconds(120)), clock);
        gate = sessions.identityGateHash();
        applications = instance("test-key");
    }

    @AfterEach void cleanOnlyRandomNamespacesAndRespectForeignKeys() {
        try {
            if (jdbc != null && gate != null) {
                jdbc.update("DELETE FROM driver_application_command WHERE gate_hash=?", gate);
                jdbc.update("DELETE FROM driver_application WHERE gate_hash=?", gate);
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
            for (byte[] key : new byte[][] { lookupKey, enc, mac }) if (key != null) Arrays.fill(key, (byte) 0);
            if (gateway != null && gateway.code != null) Arrays.fill(gateway.code, '\0');
        }
    }

    @Test void draftIsSingleOpenAndReplayReturnsTheOriginal() {
        char[] token = token(contact);
        assertFailure(AdmissionException.Code.NOT_FOUND, () -> applications.readCurrent(token, client));
        UUID key = UUID.randomUUID();
        var draft = applications.create(token, client, key, "private");
        assertEquals("draft", draft.state()); assertEquals(1, draft.version());
        assertEquals(VehicleFields.NAMES, draft.missingVehicleFields());
        assertFalse(draft.idempotentReplay());
        assertFailure(AdmissionException.Code.APPLICATION_ALREADY_OPEN, () -> applications.create(token, client, UUID.randomUUID(), "taxi"));
        var replay = applications.create(token, client, key, "private");
        assertTrue(replay.idempotentReplay()); assertEquals(draft.applicationId(), replay.applicationId());
        assertFailure(AdmissionException.Code.IDEMPOTENCY_KEY_REUSED, () -> applications.create(token, client, key, "taxi"));
        assertEquals(draft.applicationId(), applications.readCurrent(token, client).applicationId());
        assertEquals(1, count("driver_application")); assertEquals(1, count("driver_application_command"));
    }

    @Test void vehicleIsEncryptedValidatedAndVersioned() {
        char[] token = token(contact);
        var draft = applications.create(token, client, UUID.randomUUID(), "taxi");
        UUID first = UUID.randomUUID();
        var updated = applications.updateVehicle(token, client, draft.applicationId(), first, 1, CAR);
        assertEquals(2, updated.version()); assertEquals("AB 12-CD", updated.vehicleFields().registration());
        assertTrue(updated.missingVehicleFields().isEmpty());
        byte[] stored = jdbc.queryForObject("SELECT vehicle_cipher FROM driver_application WHERE gate_hash=?", byte[].class, gate);
        assertFalse(HexFormat.of().formatHex(stored).contains(HexFormat.of().formatHex("AB 12-CD".getBytes(StandardCharsets.UTF_8))));
        assertEquals(updated.vehicleFields(), applications.read(token, client, draft.applicationId()).vehicleFields());

        clock.advance(1);
        var cleared = applications.updateVehicle(token, client, draft.applicationId(), UUID.randomUUID(), 2, VehicleFields.EMPTY);
        assertEquals(3, cleared.version()); assertEquals(VehicleFields.NAMES, cleared.missingVehicleFields());
        var replay = applications.updateVehicle(token, client, draft.applicationId(), first, 1, CAR);
        assertTrue(replay.idempotentReplay()); assertEquals(2, replay.version());
        assertEquals(3, applications.readCurrent(token, client).version());
        assertFailure(AdmissionException.Code.VERSION_CONFLICT,
                () -> applications.updateVehicle(token, client, draft.applicationId(), UUID.randomUUID(), 2, CAR));
        assertFailure(AdmissionException.Code.INVALID_REQUEST, () -> applications.updateVehicle(token, client,
                draft.applicationId(), UUID.randomUUID(), 3, new VehicleFields(null, null, null, null, null, 5, 5, null)));
        assertEquals(3, applications.readCurrent(token, client).version());
    }

    @Test void anotherCandidatesFileIsIndistinguishableFromAnUnknownOne() {
        char[] mine = token(contact);
        clock.advance(61);
        char[] theirs = token(otherContact);
        var draft = applications.create(mine, client, UUID.randomUUID(), "taxi");
        applications.updateVehicle(mine, client, draft.applicationId(), UUID.randomUUID(), 1, CAR);
        assertFailure(AdmissionException.Code.NOT_FOUND, () -> applications.read(theirs, client, draft.applicationId()));
        assertFailure(AdmissionException.Code.NOT_FOUND, () -> applications.read(theirs, client, UUID.randomUUID()));
        assertFailure(AdmissionException.Code.NOT_FOUND,
                () -> applications.updateVehicle(theirs, client, draft.applicationId(), UUID.randomUUID(), 2, VehicleFields.EMPTY));
        assertFailure(AdmissionException.Code.NOT_FOUND, () -> applications.readCurrent(theirs, client));
        var own = applications.create(theirs, client, UUID.randomUUID(), "private");
        assertNotEquals(draft.applicationId(), own.applicationId());
        assertEquals(2, applications.read(mine, client, draft.applicationId()).version());
    }

    @Test void expiredReplayIsNeverReappliedAndMaintenanceKeepsMarkers() {
        char[] token = token(contact);
        UUID key = UUID.randomUUID();
        var draft = applications.create(token, client, key, "taxi");
        UUID patch = UUID.randomUUID();
        applications.updateVehicle(token, client, draft.applicationId(), patch, 1, CAR);
        clock.advance(REPLAY.toSeconds());
        assertFailure(AdmissionException.Code.REPLAY_EXPIRED, () -> applications.create(token, client, key, "taxi"));
        assertFailure(AdmissionException.Code.REPLAY_EXPIRED, () -> applications.create(token, client, key, "taxi"));
        assertEquals(1, applications.eraseExpiredResponseSecrets());
        assertFailure(AdmissionException.Code.REPLAY_EXPIRED,
                () -> applications.updateVehicle(token, client, draft.applicationId(), patch, 1, CAR));
        assertEquals(1, count("driver_application")); assertEquals(2, count("driver_application_command"));
        assertEquals(2, applications.readCurrent(token, client).version());
    }

    @Test void movedCiphertextOrForeignKeyIsUnavailableNotReadable() {
        char[] mine = token(contact);
        clock.advance(61);
        char[] theirs = token(otherContact);
        var a = applications.create(mine, client, UUID.randomUUID(), "taxi");
        var b = applications.create(theirs, client, UUID.randomUUID(), "taxi");
        applications.updateVehicle(theirs, client, b.applicationId(), UUID.randomUUID(), 1, CAR);
        var moved = jdbc.queryForMap("SELECT vehicle_cipher,version,updated_at FROM driver_application WHERE gate_hash=? AND id=?",
                gate, b.applicationId().toString());
        assertEquals(1, jdbc.update("UPDATE driver_application SET vehicle_cipher=?,version=?,updated_at=? WHERE gate_hash=? AND id=?",
                moved.get("vehicle_cipher"), moved.get("version"), moved.get("updated_at"), gate, a.applicationId().toString()));
        assertSessionFailure(SessionException.Code.SERVICE_UNAVAILABLE, () -> applications.readCurrent(mine, client));
        assertEquals("AB 12-CD", applications.readCurrent(theirs, client).vehicleFields().registration());
        assertSessionFailure(SessionException.Code.SERVICE_UNAVAILABLE, () -> instance("other-key").readCurrent(theirs, client));
    }

    @Test void revokedSessionWritesNothing() {
        char[] token = token(contact);
        assertSessionFailure(SessionException.Code.INVALID_SESSION,
                () -> applications.create(token, UUID.randomUUID().toString(), UUID.randomUUID(), "taxi"));
        sessions.revoke(token, client);
        assertSessionFailure(SessionException.Code.INVALID_SESSION, () -> applications.create(token, client, UUID.randomUUID(), "taxi"));
        assertSessionFailure(SessionException.Code.INVALID_SESSION, () -> applications.readCurrent(token, client));
        assertEquals(0, count("driver_application")); assertEquals(0, count("driver_application_command"));
    }

    @Test void concurrentCreatesOpenExactlyOneDraft() throws Exception {
        char[] token = token(contact);
        List<String> results = concurrent(8, () -> {
            try { applications.create(token.clone(), client, UUID.randomUUID(), "taxi"); return "OK"; }
            catch (AdmissionException refused) { return refused.code().name(); }
        });
        assertEquals(1, results.stream().filter("OK"::equals).count());
        assertEquals(7, results.stream().filter("APPLICATION_ALREADY_OPEN"::equals).count());
        assertEquals(1, count("driver_application"));
    }

    private PersistentDriverApplicationService instance(String keyId) {
        return new PersistentDriverApplicationService(sessions, jdbc, manager,
                new AdmissionCrypto(enc, mac, keyId, new SecureRandom()), REPLAY, clock);
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
        if (!table.equals("driver_application") && !table.equals("driver_application_command")) throw new IllegalArgumentException();
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE gate_hash=?", Long.class, gate);
    }
    private static void assertFailure(AdmissionException.Code code, Runnable operation) {
        assertEquals(code, assertThrows(AdmissionException.class, operation::run).code());
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
