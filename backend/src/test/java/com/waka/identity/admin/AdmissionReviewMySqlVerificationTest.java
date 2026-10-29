package com.waka.identity.admin;

import com.waka.identity.admission.AdmissionCrypto;
import com.waka.identity.admission.AdmissionException;
import com.waka.identity.admission.AdmissionReviewService;
import com.waka.identity.admission.PersistentDriverApplicationService;
import com.waka.identity.admission.VehicleFields;
import com.waka.identity.files.FileException;
import com.waka.identity.files.PrivateFileStore;
import com.waka.identity.files.VehiclePhotoService;
import com.waka.identity.otp.OtpCrypto;
import com.waka.identity.otp.OtpPolicy;
import com.waka.identity.otp.PersistentOtpService;
import com.waka.identity.otp.PersistedOtpProofVerifier;
import com.waka.identity.otp.SmsGateway;
import com.waka.identity.otp.SmsMessage;
import com.waka.identity.session.PersistentSessionService;
import com.waka.identity.session.SessionCrypto;
import com.waka.identity.session.SessionGrant;
import com.waka.identity.session.SessionPolicy;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.junit.jupiter.api.Assertions.*;

/** Real opt-in MySQL/V10: submission, ADMISSION examination with a TOTP session, append-only decisions. */
@EnabledIfEnvironmentVariable(named = "WAKA_VERIFY_MYSQL", matches = "true")
class AdmissionReviewMySqlVerificationTest {
    private record Login(char[] token, UUID account) { }
    private static final VehicleFields CAR = new VehicleFields("Marque", "Modele", 2020, "Bleu", "AB 12-CD", 5, 4, "holder");
    @TempDir Path storage;
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager manager;
    private OtpCrypto otpCrypto;
    private byte[][] keys;
    private MutableClock clock;
    private CapturingGateway gateway;
    private PersistentOtpService otp;
    private PersistentSessionService sessions;
    private PersistentAdminService admin;
    private PersistentDriverApplicationService applications;
    private VehiclePhotoService photos;
    private AdmissionReviewService reviews;
    private AdmissionCrypto admissionCrypto;
    private String gate, otpGate;
    private final String client = UUID.randomUUID().toString();
    private final String candidatePhone = "+2376" + "0".repeat(8);
    private final String agentPhone = "+2376" + "0".repeat(7) + "1";
    private final String thirdPhone = "+2376" + "0".repeat(7) + "2";
    private byte[] jpeg;

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
        keys = new byte[9][32];
        for (byte[] key : keys) random.nextBytes(key);
        otpCrypto = new OtpCrypto(keys[0], keys[1], random);
        clock = new MutableClock(); gateway = new CapturingGateway();
        otp = new PersistentOtpService(jdbc, manager, otpCrypto, new OtpPolicy(100), gateway, clock);
        sessions = new PersistentSessionService(jdbc, manager, new PersistedOtpProofVerifier(jdbc, otpCrypto, clock),
                new SessionCrypto(keys[2], random), new SessionPolicy(Duration.ofSeconds(7200), Duration.ofSeconds(1800)), clock);
        gate = sessions.identityGateHash();
        admin = new PersistentAdminService(sessions, jdbc, manager, new AdminCrypto(keys[3], keys[4], "test-key", random),
                new AdminPolicy(Duration.ofSeconds(900), Duration.ofSeconds(3600), 5, Duration.ofSeconds(120), Duration.ofSeconds(300)), clock);
        admissionCrypto = new AdmissionCrypto(keys[5], keys[6], "test-key", random);
        // Test-only: requirements declared qualified with no document family, never the ordinary application.
        applications = new PersistentDriverApplicationService(sessions, jdbc, manager, admissionCrypto, Duration.ofSeconds(600), clock, true);
        photos = new VehiclePhotoService(sessions, jdbc, new PrivateFileStore(storage, keys[7], keys[8], "test-key", random), admin);
        reviews = new AdmissionReviewService(sessions, admin, applications, jdbc, admissionCrypto);
        BufferedImage image = new BufferedImage(640, 480, BufferedImage.TYPE_INT_RGB);
        var out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpeg", out);
        jpeg = out.toByteArray();
    }

    @AfterEach void cleanOnlyRandomNamespacesAndRespectForeignKeys() {
        try {
            if (jdbc != null && gate != null) {
                jdbc.update("DELETE FROM audit_event WHERE correlation_ref IN (SELECT id FROM admin_session WHERE gate_hash=?)", gate);
                for (String table : List.of("admission_photo_review", "admission_decision", "vehicle_photo", "driver_application_command",
                        "driver_application", "admin_session", "admin_totp", "admin_grant", "identity_session", "identity_account", "identity_session_gate")) {
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
            if (keys != null) for (byte[] key : keys) Arrays.fill(key, (byte) 0);
            if (gateway != null && gateway.code != null) Arrays.fill(gateway.code, '\0');
        }
    }

    @Test void submissionNeedsQualifiedRequirementsCompleteVehicleAndSixPhotos() {
        Login candidate = login(candidatePhone);
        var notQualified = new PersistentDriverApplicationService(sessions, jdbc, manager, admissionCrypto, Duration.ofSeconds(600), clock);
        UUID app = applications.create(candidate.token, client, UUID.randomUUID(), "taxi").applicationId();
        assertFailure(AdmissionException.Code.REQUIREMENTS_NOT_QUALIFIED, () -> notQualified.submit(candidate.token, client, app, UUID.randomUUID(), 1));
        assertFailure(AdmissionException.Code.INCOMPLETE, () -> applications.submit(candidate.token, client, app, UUID.randomUUID(), 1));
        applications.updateVehicle(candidate.token, client, app, UUID.randomUUID(), 1, CAR);
        for (String view : List.of("PH01", "PH02", "PH03", "PH04", "PH05")) photos.upload(candidate.token, client, app, view, UUID.randomUUID(), "image/jpeg", jpeg);
        assertFailure(AdmissionException.Code.INCOMPLETE, () -> applications.submit(candidate.token, client, app, UUID.randomUUID(), 2));
        photos.upload(candidate.token, client, app, "PH06", UUID.randomUUID(), "image/jpeg", jpeg);
        assertFailure(AdmissionException.Code.VERSION_CONFLICT, () -> applications.submit(candidate.token, client, app, UUID.randomUUID(), 1));
        UUID key = UUID.randomUUID();
        var submitted = applications.submit(candidate.token, client, app, key, 2);
        assertEquals("submitted", submitted.state()); assertEquals(3, submitted.version());
        assertTrue(applications.submit(candidate.token, client, app, key, 2).idempotentReplay());
        assertFailure(AdmissionException.Code.NOT_EDITABLE, () -> applications.updateVehicle(candidate.token, client, app, UUID.randomUUID(), 3, CAR));
        assertEquals(FileException.Code.NOT_EDITABLE, assertThrows(FileException.class,
                () -> photos.upload(candidate.token, client, app, "PH01", UUID.randomUUID(), "image/jpeg", jpeg)).code());
        // Still readable: the vehicle was re-encrypted for its new version.
        assertEquals("AB 12-CD", applications.readCurrent(candidate.token, client).vehicleFields().registration());
    }

    @Test void agentExaminesRequestsCorrectionThenRefusesWithAnAuditedTrail() {
        Login candidate = login(candidatePhone);
        UUID app = submittedFile(candidate);
        clock.advance(61);
        Login agent = login(agentPhone);
        char[] adminToken = adminSession(agent, Set.of(AdminFunction.ADMISSION));
        var items = reviews.workItems(agent.token, client, adminToken);
        assertEquals(List.of(app), items.stream().map(AdmissionReviewService.WorkItem::applicationId).toList());
        var detail = reviews.detail(agent.token, client, adminToken, app);
        assertEquals("AB 12-CD", detail.application().vehicleFields().registration());
        assertEquals(6, detail.photos().size());
        UUID ph02 = detail.photos().get(1).fileId();
        assertTrue(photos.examinationContent(agent.token, client, adminToken, ph02).jpeg().length > 0);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE object_ref=? AND event_type='PRIVATE_FILE_READ' AND actor_ref=?",
                Integer.class, ph02.toString(), agent.account.toString()));

        assertFailure(AdmissionException.Code.APPROVAL_NOT_AVAILABLE, () -> reviews.decide(agent.token, client, adminToken, app,
                UUID.randomUUID(), 3, "approve", "ALL_CONDITIONS_MET", null, List.of()));
        var stalePhoto = List.of(new AdmissionReviewService.PhotoReview("PH02", UUID.randomUUID(), "to_correct", "PLATE_UNREADABLE"));
        assertFailure(AdmissionException.Code.VERSION_CONFLICT, () -> reviews.decide(agent.token, client, adminToken, app,
                UUID.randomUUID(), 3, "request_correction", "PHOTO_TO_REPLACE", null, stalePhoto));
        UUID key = UUID.randomUUID();
        var review = List.of(new AdmissionReviewService.PhotoReview("PH02", ph02, "to_correct", "PLATE_UNREADABLE"),
                new AdmissionReviewService.PhotoReview("PH01", detail.photos().getFirst().fileId(), "acceptable", null));
        var correction = reviews.decide(agent.token, client, adminToken, app, key, 3, "request_correction", "PHOTO_TO_REPLACE",
                "La plaque doit être lisible.", review);
        assertFalse(correction.idempotentReplay());
        assertTrue(reviews.decide(agent.token, client, adminToken, app, key, 3, "request_correction", "PHOTO_TO_REPLACE",
                "La plaque doit être lisible.", review).idempotentReplay());
        assertFailure(AdmissionException.Code.IDEMPOTENCY_KEY_REUSED, () -> reviews.decide(agent.token, client, adminToken, app,
                key, 3, "refuse", "PHOTO_TO_REPLACE", null, List.of()));
        assertFailure(AdmissionException.Code.VERSION_CONFLICT, () -> reviews.decide(agent.token, client, adminToken, app,
                UUID.randomUUID(), 3, "refuse", "LATE", null, List.of()));
        byte[] stored = jdbc.queryForObject("SELECT details_cipher FROM admission_decision WHERE gate_hash=?", byte[].class, gate);
        assertFalse(new String(stored, java.nio.charset.StandardCharsets.ISO_8859_1).contains("plaque"));

        var seen = reviews.candidateDecisions(candidate.token, client, app);
        assertEquals(1, seen.size());
        assertEquals("La plaque doit être lisible.", seen.getFirst().details());
        assertEquals("to_correct", seen.getFirst().photoReviews().get(1).result());
        var current = applications.readCurrent(candidate.token, client);
        assertEquals("correction_requested", current.state());
        photos.upload(candidate.token, client, app, "PH02", UUID.randomUUID(), "image/jpeg", jpeg);
        var resubmitted = applications.submit(candidate.token, client, app, UUID.randomUUID(), current.version());
        assertEquals("submitted", resubmitted.state());

        reviews.decide(agent.token, client, adminToken, app, UUID.randomUUID(), resubmitted.version(), "refuse", "VEHICLE_NOT_ELIGIBLE", null, List.of());
        assertFailure(AdmissionException.Code.NOT_FOUND, () -> applications.readCurrent(candidate.token, client));
        assertEquals("refused", applications.read(candidate.token, client, app).state());
        assertEquals(2, reviews.candidateDecisions(candidate.token, client, app).size());
        assertNotEquals(app, applications.create(candidate.token, client, UUID.randomUUID(), "private").applicationId());
        assertTrue(reviews.workItems(agent.token, client, adminToken).isEmpty());
    }

    @Test void conflictsOfInterestScopeAndFunctionAreEnforced() {
        Login agent = login(agentPhone);
        UUID own = submittedFile(agent);
        char[] adminToken = adminSession(agent, Set.of(AdminFunction.ADMISSION));
        assertTrue(reviews.workItems(agent.token, client, adminToken).isEmpty(), "own file never listed");
        assertFailure(AdmissionException.Code.CONFLICT_OF_INTEREST, () -> reviews.detail(agent.token, client, adminToken, own));
        assertFailure(AdmissionException.Code.CONFLICT_OF_INTEREST, () -> reviews.decide(agent.token, client, adminToken, own,
                UUID.randomUUID(), 3, "refuse", "SELF", null, List.of()));
        UUID ownPhoto = UUID.fromString(jdbc.queryForObject("SELECT id FROM vehicle_photo WHERE gate_hash=? AND application_id=? LIMIT 1",
                String.class, gate, own.toString()));
        assertEquals(FileException.Code.NOT_FOUND, assertThrows(FileException.class,
                () -> photos.examinationContent(agent.token, client, adminToken, ownPhoto)).code());

        clock.advance(61);
        Login candidate = login(candidatePhone);
        UUID draft = applications.create(candidate.token, client, UUID.randomUUID(), "taxi").applicationId();
        UUID draftPhoto = photos.upload(candidate.token, client, draft, "PH01", UUID.randomUUID(), "image/jpeg", jpeg).fileId();
        assertFailure(AdmissionException.Code.NOT_FOUND, () -> reviews.detail(agent.token, client, adminToken, draft), "drafts are not examined");
        assertEquals(FileException.Code.NOT_FOUND, assertThrows(FileException.class,
                () -> photos.examinationContent(agent.token, client, adminToken, draftPhoto)).code());
        assertFailure(AdmissionException.Code.NOT_FOUND, () -> reviews.candidateDecisions(candidate.token, client, own));

        clock.advance(61);
        Login auditor = login(thirdPhone);
        jdbc.update("INSERT INTO admin_grant (gate_hash,id,account_id,admin_function,granted_at,granted_by,reason_code) VALUES (?,?,?,?,NOW(6),'TEST','TEST')",
                gate, UUID.randomUUID().toString(), auditor.account.toString(), "AUDIT");
        char[] auditorToken = openSession(auditor);
        assertFailure(AdmissionException.Code.NOT_AUTHORIZED, () -> reviews.workItems(auditor.token, client, auditorToken));
        assertEquals(FileException.Code.NOT_AUTHORIZED, assertThrows(FileException.class,
                () -> photos.examinationContent(auditor.token, client, auditorToken, draftPhoto)).code());
        assertFailure(AdmissionException.Code.INVALID_ADMIN_SESSION, () -> reviews.workItems(candidate.token, client, adminToken),
                "an admin token is bound to its own client session");
    }

    private UUID submittedFile(Login owner) {
        UUID app = applications.create(owner.token, client, UUID.randomUUID(), "taxi").applicationId();
        applications.updateVehicle(owner.token, client, app, UUID.randomUUID(), 1, CAR);
        for (String view : AdmissionReviewService.VIEWS) photos.upload(owner.token, client, app, view, UUID.randomUUID(), "image/jpeg", jpeg);
        applications.submit(owner.token, client, app, UUID.randomUUID(), 2);
        return app;
    }
    private char[] adminSession(Login agent, Set<AdminFunction> functions) {
        admin.bootstrapFounder(agent.account, functions);
        return openSession(agent);
    }
    private char[] openSession(Login agent) {
        byte[] secret = decode(admin.enroll(agent.token, client).secret());
        admin.confirmEnrollment(agent.token, client, Totp.code(secret, Totp.step(clock.instant()), Totp.DIGITS));
        clock.advance(30);
        try (var grant = admin.open(agent.token, client, Totp.code(secret, Totp.step(clock.instant()), Totp.DIGITS))) {
            return grant.token();
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
    private static void assertFailure(AdmissionException.Code code, Runnable operation) { assertFailure(code, operation, ""); }
    private static void assertFailure(AdmissionException.Code code, Runnable operation, String reason) {
        assertEquals(code, assertThrows(AdmissionException.class, operation::run, reason).code(), reason);
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
