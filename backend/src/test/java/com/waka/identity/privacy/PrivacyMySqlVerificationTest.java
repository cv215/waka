package com.waka.identity.privacy;

import com.waka.identity.otp.OtpCrypto;
import com.waka.identity.otp.OtpPolicy;
import com.waka.identity.otp.PersistentOtpService;
import com.waka.identity.otp.PersistedOtpProofVerifier;
import com.waka.identity.otp.SmsGateway;
import com.waka.identity.otp.SmsMessage;
import com.waka.identity.profile.ProfileCrypto;
import com.waka.identity.session.PersistentSessionService;
import com.waka.identity.session.SessionCrypto;
import com.waka.identity.session.SessionGrant;
import com.waka.identity.session.SessionPolicy;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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

/** Real opt-in MySQL/V13; synthetic accounts and texts only. */
@EnabledIfEnvironmentVariable(named = "WAKA_VERIFY_MYSQL", matches = "true")
class PrivacyMySqlVerificationTest {
    private record Login(char[] token, UUID account) { }
    private JdbcTemplate jdbc;
    private byte[] lookupKey, enc, mac;
    private MutableClock clock;
    private CapturingGateway gateway;
    private PersistentOtpService otp;
    private PersistentSessionService sessions;
    private PersistentPrivacyService privacy;
    private String gate;
    private final List<String> otpGates = new ArrayList<>();
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
        var manager = new DataSourceTransactionManager(source);
        SecureRandom random = new SecureRandom();
        byte[] otpMac = new byte[32], otpEnc = new byte[32];
        lookupKey = new byte[32]; enc = new byte[32]; mac = new byte[32];
        for (byte[] key : new byte[][] { otpMac, otpEnc, lookupKey, enc, mac }) random.nextBytes(key);
        OtpCrypto otpCrypto = new OtpCrypto(otpMac, otpEnc, random);
        Arrays.fill(otpMac, (byte) 0); Arrays.fill(otpEnc, (byte) 0);
        clock = new MutableClock(); gateway = new CapturingGateway();
        otp = new PersistentOtpService(jdbc, manager, otpCrypto, new OtpPolicy(100), gateway, clock);
        sessions = new PersistentSessionService(jdbc, manager, new PersistedOtpProofVerifier(jdbc, otpCrypto, clock),
                new SessionCrypto(lookupKey, random), new SessionPolicy(Duration.ofSeconds(3600), Duration.ofSeconds(600)), clock);
        gate = sessions.identityGateHash();
        privacy = new PersistentPrivacyService(sessions, jdbc, new ProfileCrypto(enc, mac, "test-key", random), clock);
    }

    @AfterEach void cleanOnlyRandomNamespaces() {
        try {
            if (jdbc != null && gate != null) {
                for (String table : List.of("privacy_request_command", "privacy_request", "quote", "identity_session",
                        "identity_account", "identity_session_gate")) {
                    jdbc.update("DELETE FROM " + table + " WHERE gate_hash=?", gate);
                }
            }
            for (String otpGate : otpGates) {
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

    @Test void oneOpenRequestPerKindAndRepeatsFindIt() {
        Login login = login(contact);
        UUID key = UUID.randomUUID();
        var first = privacy.request(login.token, client, key, PersistentPrivacyService.Kind.DELETION, "Merci de supprimer mon compte");
        assertTrue(first.created());
        assertEquals("received", first.state(), "receiving deletes nothing");
        var replay = privacy.request(login.token, client, key, PersistentPrivacyService.Kind.DELETION, "Merci de supprimer mon compte");
        assertFalse(replay.created());
        assertEquals(first.requestId(), replay.requestId());
        assertEquals(PrivacyException.Code.IDEMPOTENCY_KEY_REUSED, assertThrows(PrivacyException.class,
                () -> privacy.request(login.token, client, key, PersistentPrivacyService.Kind.ACCESS, null)).code());
        clock.advance(5);
        var again = privacy.request(login.token, client, UUID.randomUUID(), PersistentPrivacyService.Kind.DELETION, null);
        assertFalse(again.created(), "a second deletion request finds the open one");
        assertEquals(first.requestId(), again.requestId());
        var access = privacy.request(login.token, client, UUID.randomUUID(), PersistentPrivacyService.Kind.ACCESS, null);
        assertTrue(access.created());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM privacy_request WHERE gate_hash=?", Integer.class, gate));
        byte[] stored = jdbc.queryForObject("SELECT details_cipher FROM privacy_request WHERE gate_hash=? AND id=?",
                byte[].class, gate, first.requestId().toString());
        assertFalse(new String(stored, StandardCharsets.ISO_8859_1).contains("supprimer"), "details encrypted");
        assertNull(jdbc.queryForObject("SELECT details_cipher FROM privacy_request WHERE gate_hash=? AND id=?",
                byte[].class, gate, access.requestId().toString()));
        assertEquals("ACTIVE", jdbc.queryForObject("SELECT status FROM identity_account WHERE gate_hash=? AND id=?",
                String.class, gate, login.account.toString()), "the account is not closed by a request");
    }

    @Test void activityShowsOnlyOwnObjectsNewestFirstWithACursor() {
        Login login = login(contact);
        var deletion = privacy.request(login.token, client, UUID.randomUUID(), PersistentPrivacyService.Kind.DELETION, null);
        clock.advance(10);
        UUID quote = quote(login.account, clock.instant());
        clock.advance(10);
        var access = privacy.request(login.token, client, UUID.randomUUID(), PersistentPrivacyService.Kind.ACCESS, null);
        var page = privacy.activity(login.token, client, null, 2);
        assertEquals(List.of(access.requestId(), quote), page.items().stream().map(PersistentPrivacyService.ActivityItem::objectId).toList());
        assertEquals("valid", page.items().get(1).state());
        assertNotNull(page.nextCursor());
        var rest = privacy.activity(login.token, client, page.nextCursor(), 2);
        assertEquals(List.of(deletion.requestId()), rest.items().stream().map(PersistentPrivacyService.ActivityItem::objectId).toList());
        assertNull(rest.nextCursor());
        clock.advance(400);
        assertEquals("expired", privacy.activity(login.token, client, null, 10).items().stream()
                .filter(i -> i.objectId().equals(quote)).findFirst().orElseThrow().state());
        clock.advance(61);
        Login other = login(otherContact);
        assertTrue(privacy.activity(other.token, client, null, 50).items().isEmpty(), "no object of another account");
        assertEquals(PrivacyException.Code.INVALID_REQUEST, assertThrows(PrivacyException.class,
                () -> privacy.activity(login.token, client, "123_not-a-uuid", 10)).code());
    }

    /** A synthetic quote row on the real launch-v1 grid; the quote service itself is covered by its own test. */
    private UUID quote(UUID account, Instant at) {
        UUID id = UUID.randomUUID();
        LocalDateTime created = LocalDateTime.ofInstant(at, ZoneOffset.UTC);
        jdbc.update("INSERT INTO quote (gate_hash,id,account_id,request_mac,payload_mac,mode,passenger_count,distance_metres,fare_version,"
                + "band_code,place_price_xaf,total_xaf,key_id,points_cipher,created_at,expires_at) VALUES (?,?,?,?,?,'shared',1,500,'launch-v1','B01',150,150,'test-key',?,?,?)",
                gate, id.toString(), account.toString(), "a".repeat(64), "b".repeat(64), new byte[32], created, created.plusMinutes(5));
        return id;
    }
    private Login login(String phone) {
        var challenge = otp.request(client, UUID.randomUUID(), phone);
        otpGates.add(jdbc.queryForObject("SELECT global_gate FROM otp_challenge WHERE id=?", String.class, challenge.id().toString()));
        char[] code = gateway.code.clone();
        PersistentOtpService.Proof proof;
        try { proof = otp.verify(challenge.id(), client, UUID.randomUUID(), code); }
        finally { Arrays.fill(code, '\0'); }
        try (SessionGrant grant = sessions.open(proof, client, phone)) { return new Login(grant.token(), grant.accountId()); }
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
        private final AtomicReference<Instant> time = new AtomicReference<>(Instant.parse("2026-10-29T10:00:00Z"));
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) throw new IllegalArgumentException(); return this;
        }
        @Override public Instant instant() { return time.get(); }
        void advance(long seconds) { time.updateAndGet(value -> value.plusSeconds(seconds)); }
    }
}
