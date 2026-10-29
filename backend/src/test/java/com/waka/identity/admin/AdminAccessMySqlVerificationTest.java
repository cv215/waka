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

/** Real opt-in MySQL/V8; random keys and TOTP secrets, synthetic accounts only. */
@EnabledIfEnvironmentVariable(named = "WAKA_VERIFY_MYSQL", matches = "true")
class AdminAccessMySqlVerificationTest {
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
                for (String table : List.of("admin_session", "admin_totp", "admin_grant", "identity_session", "identity_account", "identity_session_gate")) {
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

    @Test void founderBootstrapHappensOnceForAnActiveAccountOnly() {
        Login founder = login(contact);
        assertFailure(AdminException.Code.ACCOUNT_NOT_FOUND, () -> admin.bootstrapFounder(UUID.randomUUID(), Set.of(AdminFunction.ADMISSION)));
        admin.bootstrapFounder(founder.account, EnumSet.allOf(AdminFunction.class));
        assertEquals(5, jdbc.queryForObject("SELECT COUNT(*) FROM admin_grant WHERE gate_hash=? AND granted_by='FOUNDER_BOOTSTRAP'", Integer.class, gate));
        assertFailure(AdminException.Code.BOOTSTRAP_ALREADY_DONE, () -> admin.bootstrapFounder(founder.account, Set.of(AdminFunction.AUDIT)));
        clock.advance(61);
        Login other = login(otherContact);
        assertFailure(AdminException.Code.BOOTSTRAP_ALREADY_DONE, () -> admin.bootstrapFounder(other.account, Set.of(AdminFunction.ADMISSION)));
        assertFailure(AdminException.Code.NOT_AUTHORIZED, () -> admin.enroll(other.token, client));
        assertFailure(AdminException.Code.NOT_AUTHORIZED, () -> admin.open(other.token, client, "123456"));
    }

    @Test void clientSessionAloneNeverOpensAdministration() {
        Login founder = login(contact);
        admin.bootstrapFounder(founder.account, Set.of(AdminFunction.ADMISSION));
        assertFailure(AdminException.Code.ENROLLMENT_REQUIRED, () -> admin.open(founder.token, client, "123456"));
        byte[] secret = decode(admin.enroll(founder.token, client).secret());
        byte[] stored = jdbc.queryForObject("SELECT secret_cipher FROM admin_totp WHERE gate_hash=?", byte[].class, gate);
        assertFalse(HexFormat.of().formatHex(stored).contains(HexFormat.of().formatHex(secret)));
        assertFailure(AdminException.Code.ENROLLMENT_REQUIRED, () -> admin.open(founder.token, client, current(secret)), "pending factor");
        assertFailure(AdminException.Code.INVALID_SECOND_FACTOR, () -> admin.confirmEnrollment(founder.token, client, wrong(secret)));
        admin.confirmEnrollment(founder.token, client, current(secret));
        assertFailure(AdminException.Code.ALREADY_ENROLLED, () -> admin.enroll(founder.token, client));
        assertFailure(AdminException.Code.INVALID_SECOND_FACTOR, () -> admin.open(founder.token, client, current(secret)), "same code twice");
        clock.advance(30);
        try (var grant = admin.open(founder.token, client, current(secret))) {
            char[] adminToken = grant.token();
            assertEquals(List.of(AdminFunction.ADMISSION), grant.context().functions());
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM admin_session WHERE gate_hash=? AND token_hash=?",
                    Integer.class, gate, new String(adminToken)), "only an imprint is stored");
            assertTrue(admin.authenticate(founder.token, client, adminToken).allows(AdminFunction.ADMISSION));
            assertFalse(admin.authenticate(founder.token, client, adminToken).allows(AdminFunction.FINANCE));
        }
    }

    @Test void repeatedFailuresLockTemporarily() {
        Login founder = login(contact);
        byte[] secret = enrolled(founder);
        clock.advance(30);
        assertFailure(AdminException.Code.INVALID_SECOND_FACTOR, () -> admin.open(founder.token, client, wrong(secret)));
        assertFailure(AdminException.Code.INVALID_SECOND_FACTOR, () -> admin.open(founder.token, client, wrong(secret)));
        assertFailure(AdminException.Code.LOCKED, () -> admin.open(founder.token, client, wrong(secret)));
        assertFailure(AdminException.Code.LOCKED, () -> admin.open(founder.token, client, current(secret)), "even a right code");
        clock.advance(POLICY.lockDuration().toSeconds());
        admin.open(founder.token, client, current(secret)).close();
    }

    @Test void administrationDiesWithItsClientSessionGrantOrIdleTime() {
        Login founder = login(contact);
        byte[] secret = enrolled(founder);
        clock.advance(30);
        char[] adminToken;
        try (var grant = admin.open(founder.token, client, current(secret))) { adminToken = grant.token(); }
        clock.advance(61);
        assertFailure(AdminException.Code.INVALID_ADMIN_SESSION, () -> admin.authenticate(founder.token, client, adminToken), "idle");
        clock.advance(30);
        char[] second;
        try (var grant = admin.open(founder.token, client, current(secret))) { second = grant.token(); }
        jdbc.update("UPDATE admin_grant SET revoked_at=granted_at,revoked_by='TEST',revoke_reason='TEST' WHERE gate_hash=?", gate);
        assertFailure(AdminException.Code.NOT_AUTHORIZED, () -> admin.authenticate(founder.token, client, second));
        assertEquals("NO_GRANT", jdbc.queryForObject("SELECT end_reason FROM admin_session WHERE gate_hash=? AND ended_at IS NOT NULL ORDER BY created_at DESC LIMIT 1",
                String.class, gate));
    }

    @Test void adminTokenIsBoundToItsClientSessionAndAccount() {
        Login founder = login(contact);
        byte[] secret = enrolled(founder);
        clock.advance(30);
        char[] adminToken;
        try (var grant = admin.open(founder.token, client, current(secret))) { adminToken = grant.token(); }
        clock.advance(31);
        Login other = login(otherContact);
        assertFailure(AdminException.Code.INVALID_ADMIN_SESSION, () -> admin.authenticate(other.token, client, adminToken));
        clock.advance(61);
        Login again = login(contact);
        assertFailure(AdminException.Code.INVALID_ADMIN_SESSION, () -> admin.authenticate(again.token, client, adminToken), "other client session");
        sessions.revoke(founder.token, client);
        assertSessionFailure(SessionException.Code.INVALID_SESSION, () -> admin.authenticate(founder.token, client, adminToken));
    }

    @Test void explicitCloseAndFactorResetEndSessions() {
        Login founder = login(contact);
        byte[] secret = enrolled(founder);
        clock.advance(30);
        char[] first;
        try (var grant = admin.open(founder.token, client, current(secret))) { first = grant.token(); }
        admin.close(founder.token, client, first);
        assertFailure(AdminException.Code.INVALID_ADMIN_SESSION, () -> admin.authenticate(founder.token, client, first));
        assertFailure(AdminException.Code.INVALID_ADMIN_SESSION, () -> admin.close(founder.token, client, first));
        clock.advance(30);
        char[] second;
        try (var grant = admin.open(founder.token, client, current(secret))) { second = grant.token(); }
        admin.resetSecondFactor(founder.account);
        assertFailure(AdminException.Code.INVALID_ADMIN_SESSION, () -> admin.authenticate(founder.token, client, second));
        assertFailure(AdminException.Code.ENROLLMENT_REQUIRED, () -> admin.open(founder.token, client, current(secret)));
        assertNotNull(admin.enroll(founder.token, client).secret());
    }


    @Test void grantsAreDatedReasonedNeverSelfGrantedAndAudited() {
        Login founder = login(contact);
        admin.bootstrapFounder(founder.account, EnumSet.allOf(AdminFunction.class));
        byte[] secret = decode(admin.enroll(founder.token, client).secret());
        admin.confirmEnrollment(founder.token, client, current(secret));
        clock.advance(30);
        char[] adminToken;
        try (var grant = admin.open(founder.token, client, current(secret))) { adminToken = grant.token(); }
        clock.advance(31);
        Login other = login(otherContact);
        assertFailure(AdminException.Code.SELF_GRANT_FORBIDDEN,
                () -> admin.grant(founder.token, client, adminToken, founder.account, AdminFunction.FINANCE, "STAFFING"));
        assertFailure(AdminException.Code.NOT_FOUND,
                () -> admin.grant(founder.token, client, adminToken, UUID.randomUUID(), AdminFunction.ADMISSION, "STAFFING"));
        assertFailure(AdminException.Code.INVALID_REQUEST,
                () -> admin.grant(founder.token, client, adminToken, other.account, AdminFunction.ADMISSION, "free text"));
        var granted = admin.grant(founder.token, client, adminToken, other.account, AdminFunction.ADMISSION, "STAFFING");
        assertFalse(granted.idempotentReplay());
        assertEquals(founder.account.toString(), granted.grantedBy());
        var again = admin.grant(founder.token, client, adminToken, other.account, AdminFunction.ADMISSION, "STAFFING");
        assertTrue(again.idempotentReplay());
        assertEquals(granted.grantId(), again.grantId(), "a repeated command has a single effect");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM admin_grant WHERE gate_hash=? AND account_id=?",
                Integer.class, gate, other.account.toString()));
        assertTrue(admin.grants(founder.token, client, adminToken).stream().anyMatch(g -> g.grantId().equals(granted.grantId())));

        UUID own = jdbc.queryForObject("SELECT id FROM admin_grant WHERE gate_hash=? AND account_id=? AND admin_function='HABILITATIONS'",
                (rs, n) -> UUID.fromString(rs.getString(1)), gate, founder.account.toString());
        assertFailure(AdminException.Code.SELF_GRANT_FORBIDDEN, () -> admin.revoke(founder.token, client, adminToken, own, "LEFT"));
        assertFailure(AdminException.Code.NOT_FOUND, () -> admin.revoke(founder.token, client, adminToken, UUID.randomUUID(), "LEFT"));
        clock.advance(5);
        var revoked = admin.revoke(founder.token, client, adminToken, granted.grantId(), "LEFT_TEAM");
        assertEquals("LEFT_TEAM", revoked.revokeReason());
        assertNotNull(revoked.revokedAt());
        var repeated = admin.revoke(founder.token, client, adminToken, granted.grantId(), "OTHER_REASON");
        assertEquals(revoked.revokedAt(), repeated.revokedAt(), "already revoked: unchanged");
        assertEquals("LEFT_TEAM", repeated.revokeReason());

        var entries = admin.audit(founder.token, client, adminToken, "admin_grant", granted.grantId().toString(), null, 10);
        assertEquals(List.of("ADMIN_REVOKE", "ADMIN_GRANT"), entries.stream().map(PersistentAdminService.AuditView::eventType).toList());
        assertTrue(entries.stream().allMatch(e -> founder.account.toString().equals(e.actorRef())));
        var first = admin.audit(founder.token, client, adminToken, "admin_grant", granted.grantId().toString(), null, 1);
        var next = admin.audit(founder.token, client, adminToken, "admin_grant", granted.grantId().toString(), first.getFirst().id(), 1);
        assertEquals("ADMIN_REVOKE", first.getFirst().eventType());
        assertEquals("ADMIN_GRANT", next.getFirst().eventType());
        assertFailure(AdminException.Code.INVALID_REQUEST,
                () -> admin.audit(founder.token, client, adminToken, "admin grant", null, null, 10));
        assertFailure(AdminException.Code.INVALID_REQUEST,
                () -> admin.audit(founder.token, client, adminToken, null, null, null, 101));
    }

    @Test void grantsAndAuditNeedTheirOwnFunction() {
        Login founder = login(contact);
        admin.bootstrapFounder(founder.account, Set.of(AdminFunction.ADMISSION));
        byte[] secret = decode(admin.enroll(founder.token, client).secret());
        admin.confirmEnrollment(founder.token, client, current(secret));
        clock.advance(30);
        char[] adminToken;
        try (var grant = admin.open(founder.token, client, current(secret))) { adminToken = grant.token(); }
        assertFailure(AdminException.Code.NOT_AUTHORIZED, () -> admin.grants(founder.token, client, adminToken));
        assertFailure(AdminException.Code.NOT_AUTHORIZED,
                () -> admin.grant(founder.token, client, adminToken, UUID.randomUUID(), AdminFunction.AUDIT, "STAFFING"));
        assertFailure(AdminException.Code.NOT_AUTHORIZED, () -> admin.audit(founder.token, client, adminToken, null, null, null, 10));
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
