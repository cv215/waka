package com.waka.identity.files;

import com.waka.identity.admission.AdmissionCrypto;
import com.waka.identity.admission.PersistentDriverApplicationService;
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
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.junit.jupiter.api.Assertions.*;

/** Real opt-in MySQL/V9 with a temporary private directory; synthetic images only. */
@EnabledIfEnvironmentVariable(named = "WAKA_VERIFY_MYSQL", matches = "true")
class VehiclePhotoMySqlVerificationTest {
    private record Login(char[] token, UUID account) { }
    @TempDir Path storage;
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager manager;
    private OtpCrypto otpCrypto;
    private byte[] lookupKey, appEnc, appMac, fileEnc, fileMac;
    private MutableClock clock;
    private CapturingGateway gateway;
    private PersistentOtpService otp;
    private PersistentSessionService sessions;
    private PersistentDriverApplicationService applications;
    private VehiclePhotoService photos;
    private String gate, otpGate;
    private final String client = UUID.randomUUID().toString();
    private final String contact = "+2376" + "0".repeat(8);
    private final String otherContact = "+2376" + "0".repeat(7) + "1";
    private byte[] jpeg, png;

    @BeforeEach void prepare() throws Exception {
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
        lookupKey = new byte[32]; appEnc = new byte[32]; appMac = new byte[32]; fileEnc = new byte[32]; fileMac = new byte[32];
        for (byte[] key : new byte[][] { otpMac, otpEnc, lookupKey, appEnc, appMac, fileEnc, fileMac }) random.nextBytes(key);
        otpCrypto = new OtpCrypto(otpMac, otpEnc, random);
        Arrays.fill(otpMac, (byte) 0); Arrays.fill(otpEnc, (byte) 0);
        clock = new MutableClock(); gateway = new CapturingGateway();
        otp = new PersistentOtpService(jdbc, manager, otpCrypto, new OtpPolicy(100), gateway, clock);
        sessions = new PersistentSessionService(jdbc, manager, new PersistedOtpProofVerifier(jdbc, otpCrypto, clock),
                new SessionCrypto(lookupKey, random), new SessionPolicy(Duration.ofSeconds(3600), Duration.ofSeconds(600)), clock);
        gate = sessions.identityGateHash();
        applications = new PersistentDriverApplicationService(sessions, jdbc, manager,
                new AdmissionCrypto(appEnc, appMac, "test-key", random), Duration.ofSeconds(60), clock);
        photos = new VehiclePhotoService(sessions, jdbc, new PrivateFileStore(storage, fileEnc, fileMac, "test-key", random));
        jpeg = PhotoSanitizerTest.withGpsSegment(PhotoSanitizerTest.image("jpeg", 640, 480, BufferedImage.TYPE_INT_RGB));
        png = PhotoSanitizerTest.image("png", 400, 300, BufferedImage.TYPE_INT_ARGB);
    }

    @AfterEach void cleanOnlyRandomNamespacesAndRespectForeignKeys() {
        try {
            if (jdbc != null && gate != null) {
                for (String table : List.of("vehicle_photo", "driver_application_command", "driver_application",
                        "identity_session", "identity_account", "identity_session_gate")) {
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
            for (byte[] key : new byte[][] { lookupKey, appEnc, appMac, fileEnc, fileMac }) if (key != null) Arrays.fill(key, (byte) 0);
            if (gateway != null && gateway.code != null) Arrays.fill(gateway.code, '\0');
        }
    }

    @Test void ownerUploadsSixViewsAndReadsBackCleanedContent() throws Exception {
        Login me = login(contact);
        UUID app = applications.create(me.token, client, UUID.randomUUID(), "taxi").applicationId();
        assertTrue(photos.list(me.token, client, app).stream().allMatch(v -> "missing".equals(v.state())));
        for (String view : VehiclePhotoService.VIEWS) {
            var photo = photos.upload(me.token, client, app, view, UUID.randomUUID(), "image/jpeg", jpeg);
            assertEquals("received", photo.state()); assertEquals(640, photo.width());
        }
        var listed = photos.list(me.token, client, app);
        assertEquals(VehiclePhotoService.VIEWS, listed.stream().map(VehiclePhotoService.ViewStatus::view).toList());
        assertTrue(listed.stream().allMatch(v -> "received".equals(v.state())));
        byte[] content = photos.content(me.token, client, listed.getFirst().fileId()).jpeg();
        assertFalse(new String(content, java.nio.charset.StandardCharsets.ISO_8859_1).contains("GPSLatitude"));
        try (var files = Files.list(storage)) { assertEquals(6, files.count(), "one encrypted file per view, no leftover"); }
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM vehicle_photo WHERE gate_hash=? AND content_type<>'image/jpeg'", Integer.class, gate));
    }

    @Test void replacementKeepsHistoryAndReplayDoesNotDuplicate() throws Exception {
        Login me = login(contact);
        UUID app = applications.create(me.token, client, UUID.randomUUID(), "private").applicationId();
        UUID key = UUID.randomUUID();
        var first = photos.upload(me.token, client, app, "PH02", key, "image/jpeg", jpeg);
        var replay = photos.upload(me.token, client, app, "PH02", key, "image/jpeg", jpeg);
        assertTrue(replay.idempotentReplay()); assertEquals(first.fileId(), replay.fileId());
        assertFailure(FileException.Code.IDEMPOTENCY_KEY_REUSED, () -> photos.upload(me.token, client, app, "PH02", key, "image/png", png));
        var second = photos.upload(me.token, client, app, "PH02", UUID.randomUUID(), "image/png", png);
        assertEquals(second.fileId(), photos.list(me.token, client, app).get(1).fileId());
        assertEquals("replaced", photos.upload(me.token, client, app, "PH02", key, "image/jpeg", jpeg).state());
        assertNotNull(photos.content(me.token, client, first.fileId()).jpeg(), "history readable by its owner");
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM vehicle_photo WHERE gate_hash=?", Integer.class, gate));
        try (var files = Files.list(storage)) { assertEquals(2, files.count(), "replays and refusals leave no orphan file"); }
    }

    @Test void anotherAccountSeesNothingAndRefusedUploadsLeaveNoFile() throws Exception {
        Login me = login(contact);
        clock.advance(61);
        Login other = login(otherContact);
        UUID app = applications.create(me.token, client, UUID.randomUUID(), "taxi").applicationId();
        var mine = photos.upload(me.token, client, app, "PH01", UUID.randomUUID(), "image/jpeg", jpeg);
        assertFailure(FileException.Code.NOT_FOUND, () -> photos.content(other.token, client, mine.fileId()));
        assertFailure(FileException.Code.NOT_FOUND, () -> photos.list(other.token, client, app));
        assertFailure(FileException.Code.NOT_FOUND, () -> photos.upload(other.token, client, app, "PH01", UUID.randomUUID(), "image/jpeg", jpeg));
        assertFailure(FileException.Code.NOT_FOUND, () -> photos.upload(me.token, client, UUID.randomUUID(), "PH01", UUID.randomUUID(), "image/jpeg", jpeg));
        assertFailure(FileException.Code.INVALID_FILE, () -> photos.upload(me.token, client, app, "PH03", UUID.randomUUID(), "image/png", jpeg));
        sessions.revoke(me.token, client);
        assertSessionFailure(() -> photos.upload(me.token, client, app, "PH03", UUID.randomUUID(), "image/jpeg", jpeg));
        assertSessionFailure(() -> photos.content(me.token, client, mine.fileId()));
        try (var files = Files.list(storage)) { assertEquals(1, files.count()); }
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM vehicle_photo WHERE gate_hash=?", Integer.class, gate));
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
    private static void assertFailure(FileException.Code code, Runnable operation) {
        assertEquals(code, assertThrows(FileException.class, operation::run).code());
    }
    private static void assertSessionFailure(Runnable operation) {
        assertEquals(SessionException.Code.INVALID_SESSION, assertThrows(SessionException.class, operation::run).code());
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
