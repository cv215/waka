package com.waka.identity.admin;

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
import java.io.ByteArrayOutputStream;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.junit.jupiter.api.Assertions.*;

/** Real opt-in MySQL/V21: PRIVACY and ASSISTANCE handling; synthetic accounts, texts and voyages only. */
@EnabledIfEnvironmentVariable(named = "WAKA_VERIFY_MYSQL", matches = "true")
class AssistanceMySqlVerificationTest {
    private record Login(char[] token, UUID account) { }
    private static final AdminPolicy POLICY = new AdminPolicy(Duration.ofSeconds(60), Duration.ofSeconds(300), 3,
            Duration.ofSeconds(120), Duration.ofSeconds(300));
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager manager;
    private OtpCrypto otpCrypto;
    private byte[] lookupKey, enc, mac;
    private MutableClock clock;
    private CapturingGateway gateway;
    private PersistentOtpService otp;
    private PersistentSessionService sessions;
    private PersistentAdminService admin;
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
                new SessionCrypto(lookupKey, random), new SessionPolicy(Duration.ofSeconds(3600), Duration.ofSeconds(600)), clock);
        gate = sessions.identityGateHash();
        admin = new PersistentAdminService(sessions, jdbc, manager, new AdminCrypto(enc, mac, "test-key", random), POLICY, clock);
    }

    @AfterEach void cleanOnlyRandomNamespacesAndRespectForeignKeys() {
        try {
            if (jdbc != null && gate != null) {
                jdbc.update("DELETE FROM audit_event WHERE object_type='admin_grant' AND object_ref IN (SELECT id FROM admin_grant WHERE gate_hash=?)", gate);
                jdbc.update("DELETE FROM audit_event WHERE correlation_ref IN (SELECT id FROM admin_session WHERE gate_hash=?)", gate);
                for (String table : List.of("voyage", "driver_application", "privacy_request_command", "privacy_request", "admin_session", "admin_totp",
                        "admin_grant", "identity_session", "identity_account", "identity_session_gate")) {
                    jdbc.update("DELETE FROM " + table + " WHERE gate_hash=?", gate);
                }
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

    @Test void privacyAgentReadsWithAuditAndClosesWithAReason() {
        Login founder = login(contact);
        char[] adminToken = openAll(founder);
        clock.advance(31);
        Login person = login(otherContact);
        byte[] enc = new byte[32], mac = new byte[32];
        new SecureRandom().nextBytes(enc); new SecureRandom().nextBytes(mac);
        var crypto = new com.waka.identity.profile.ProfileCrypto(enc, mac, "test-key", new SecureRandom());
        var requests = new com.waka.identity.privacy.PersistentPrivacyService(sessions, jdbc, crypto, clock);
        var handling = new com.waka.identity.privacy.PrivacyAdminService(sessions, admin, jdbc, crypto);
        var received = requests.request(person.token, client, UUID.randomUUID(), com.waka.identity.privacy.PersistentPrivacyService.Kind.DELETION,
                "Merci de fermer mon compte");
        var listed = handling.requests(founder.token, client, adminToken);
        assertEquals("Merci de fermer mon compte", listed.getFirst().details());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE event_type='PRIVACY_REQUEST_READ' AND object_ref=?",
                Integer.class, received.requestId().toString()), "reading the text is audited");
        var closed = handling.close(founder.token, client, adminToken, received.requestId(), "completed", "ACCOUNT_CLOSED_BY_PROCEDURE");
        assertEquals("closed", closed.state());
        assertEquals("completed", closed.outcome());
        assertEquals("closed", handling.close(founder.token, client, adminToken, received.requestId(), "refused", "OTHER").state(), "repeat");
        assertEquals("completed", jdbc.queryForObject("SELECT outcome FROM privacy_request WHERE gate_hash=? AND id=?", String.class, gate,
                received.requestId().toString()), "the first closure stays");
        assertTrue(requests.request(person.token, client, UUID.randomUUID(), com.waka.identity.privacy.PersistentPrivacyService.Kind.DELETION, null).created(),
                "once closed, a new request can be made");
        var own = requests.request(founder.token, client, UUID.randomUUID(), com.waka.identity.privacy.PersistentPrivacyService.Kind.ACCESS, null);
        assertTrue(handling.requests(founder.token, client, adminToken).stream().noneMatch(r -> r.requestId().equals(own.requestId())),
                "an agent never sees their own request");
        assertEquals(AdminException.Code.NOT_FOUND, assertThrows(AdminException.class,
                () -> handling.close(founder.token, client, adminToken, own.requestId(), "completed", "SELF")).code());
    }

    @Test void assistanceUnlocksALockedVoyageWithAuditButNotTheirOwn() {
        Login founder = login(contact);
        char[] adminToken = openAll(founder);
        clock.advance(31);
        Login driver = login(otherContact);
        UUID voyage = lockedVoyage(driver.account);
        UUID ownVoyage = lockedVoyage(founder.account);
        var assistance = new com.waka.mobility.voyage.VoyageAssistanceService(sessions, admin, jdbc);
        assertEquals(List.of(voyage), assistance.locked(founder.token, client, adminToken).stream()
                .map(com.waka.mobility.voyage.VoyageAssistanceService.LockedVoyage::voyageId).toList());
        assertEquals(0, assistance.unlock(founder.token, client, adminToken, voyage, "PASSENGER_IDENTIFIED").failures());
        assertEquals(0, jdbc.queryForObject("SELECT boarding_failures FROM voyage WHERE gate_hash=? AND id=?", Integer.class, gate, voyage.toString()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE event_type='VOYAGE_BOARDING_UNLOCKED' AND object_ref=?",
                Integer.class, voyage.toString()));
        assertEquals(AdminException.Code.NOT_FOUND, assertThrows(AdminException.class,
                () -> assistance.unlock(founder.token, client, adminToken, ownVoyage, "SELF")).code());
    }

    /** A locked voyage in synthetic test data; its file row is never decrypted by the services under test. */
    private UUID lockedVoyage(UUID driver) {
        UUID application = UUID.randomUUID(), voyage = UUID.randomUUID();
        var now = java.time.LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        jdbc.update("INSERT INTO driver_application (gate_hash,id,account_id,requested_category,state,open_marker,version,key_id,vehicle_cipher,created_at,updated_at,submitted_at) "
                + "VALUES (?,?,?,'taxi','approved',1,3,'test-key',?,?,?,?)", gate, application.toString(), driver.toString(), new byte[32], now, now, now);
        jdbc.update("INSERT INTO voyage (gate_hash,id,driver_account_id,application_id,category,seats,state,departure_at,request_mac,payload_mac,created_at,boarding_failures) "
                + "VALUES (?,?,?,?,'taxi',4,'published',?,?,?,?,10)", gate, voyage.toString(), driver.toString(), application.toString(),
                now.plusDays(1), voyage.toString().replace("-", "").repeat(2), "b".repeat(64), now);
        return voyage;
    }
    private char[] openAll(Login founder) {
        admin.bootstrapFounder(founder.account, EnumSet.allOf(AdminFunction.class));
        byte[] secret = decode(admin.enroll(founder.token, client).secret());
        admin.confirmEnrollment(founder.token, client, current(secret));
        clock.advance(30);
        try (var grant = admin.open(founder.token, client, current(secret))) { return grant.token(); }
    }
    private byte[] enrolled(Login founder) {
        admin.bootstrapFounder(founder.account, Set.of(AdminFunction.ADMISSION, AdminFunction.AUDIT));
        byte[] secret = decode(admin.enroll(founder.token, client).secret());
        admin.confirmEnrollment(founder.token, client, current(secret));
        return secret;
    }
    private String current(byte[] secret) { return Totp.code(secret, Totp.step(clock.instant()), Totp.DIGITS); }
    private String wrong(byte[] secret) {
        long step = Totp.step(clock.instant());
        for (int candidate = 0; ; candidate++) {
            String code = String.format("%06d", candidate);
            boolean nearby = false;
            for (long s = step - 1; s <= step + 1; s++) nearby |= Totp.code(secret, s, Totp.DIGITS).equals(code);
            if (!nearby) return code;
        }
    }
    private static byte[] decode(String base32) {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
        var out = new ByteArrayOutputStream();
        int buffer = 0, bits = 0;
        for (char c : base32.toCharArray()) {
            buffer = (buffer << 5) | alphabet.indexOf(c); bits += 5;
            if (bits >= 8) { out.write((buffer >> (bits - 8)) & 0xff); bits -= 8; }
        }
        return out.toByteArray();
    }
    private Login login(String phone) {
        var challenge = otp.request(client, UUID.randomUUID(), phone);
        otpGate = jdbc.queryForObject("SELECT global_gate FROM otp_challenge WHERE id=?", String.class, challenge.id().toString());
        char[] code = gateway.code.clone();
        PersistentOtpService.Proof proof;
        try { proof = otp.verify(challenge.id(), client, UUID.randomUUID(), code); }
        finally { Arrays.fill(code, '\0'); }
        try (SessionGrant grant = sessions.open(proof, client, phone)) { return new Login(grant.token(), grant.accountId()); }
    }
    private static void assertFailure(AdminException.Code code, Runnable operation) { assertFailure(code, operation, ""); }
    private static void assertFailure(AdminException.Code code, Runnable operation, String reason) {
        assertEquals(code, assertThrows(AdminException.class, operation::run, reason).code(), reason);
    }
    private static void assertSessionFailure(SessionException.Code code, Runnable operation) {
        assertEquals(code, assertThrows(SessionException.class, operation::run).code());
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
        // Aligned on a 30 s step so that advancing by 30 s always reaches a fresh TOTP step.
        private final AtomicReference<Instant> time = new AtomicReference<>(Instant.parse("2026-10-28T10:00:00Z"));
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) throw new IllegalArgumentException(); return this;
        }
        @Override public Instant instant() { return time.get(); }
        void advance(long seconds) { time.updateAndGet(value -> value.plusSeconds(seconds)); }
    }
}

