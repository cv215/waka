package com.waka.identity.admin;

import com.waka.identity.admission.AdmissionCrypto;
import com.waka.identity.admission.AdmissionException;
import com.waka.identity.admission.AdmissionReviewService;
import com.waka.identity.admission.DocumentCatalog;
import com.waka.identity.admission.PersistentDriverApplicationService;
import com.waka.identity.admission.PersistentDriverApplicationService.RequirementStatus;
import com.waka.identity.admission.VehicleFields;
import com.waka.identity.files.DriverDocumentService;
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
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
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

/**
 * Real opt-in MySQL/V11, both routes end to end: candidate documents, submission, ADMISSION examination
 * with a TOTP session, correction of one piece only, renewal reuse and approval rules. Catalogue v1
 * keeps approval blocked; a test catalogue with every requirement established proves the approval path.
 */
@EnabledIfEnvironmentVariable(named = "WAKA_VERIFY_MYSQL", matches = "true")
class AdmissionReviewMySqlVerificationTest {
    private record Login(char[] token, UUID account) { }
    /** The three services that depend on a catalogue, wired together. */
    private record Stack(PersistentDriverApplicationService applications, DriverDocumentService documents, AdmissionReviewService reviews) { }
    private static final List<DocumentCatalog.Requirement> ALL_ESTABLISHED = DocumentCatalog.V1.stream()
            .map(r -> new DocumentCatalog.Requirement(r.code(), r.document(), r.explanation(), r.categories(),
                    r.onlyForAuthorizedUse(), r.scope(), DocumentCatalog.Status.ESTABLISHED)).toList();
    @TempDir Path storage;
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager manager;
    private byte[][] keys;
    private MutableClock clock;
    private CapturingGateway gateway;
    private PersistentOtpService otp;
    private PersistentSessionService sessions;
    private PersistentAdminService admin;
    private PrivateFileStore store;
    private AdmissionCrypto admissionCrypto;
    private VehiclePhotoService photos;
    private Stack v1, established, current;
    private String gate, otpGate;
    private final String client = UUID.randomUUID().toString();
    private final String candidatePhone = "+2376" + "0".repeat(8);
    private final String agentPhone = "+2376" + "0".repeat(7) + "1";
    private final String thirdPhone = "+2376" + "0".repeat(7) + "2";
    private byte[] jpeg;
    private LocalDate nextYear;

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
        OtpCrypto otpCrypto = new OtpCrypto(keys[0], keys[1], random);
        clock = new MutableClock(); gateway = new CapturingGateway();
        otp = new PersistentOtpService(jdbc, manager, otpCrypto, new OtpPolicy(100), gateway, clock);
        sessions = new PersistentSessionService(jdbc, manager, new PersistedOtpProofVerifier(jdbc, otpCrypto, clock),
                new SessionCrypto(keys[2], random), new SessionPolicy(Duration.ofSeconds(7200), Duration.ofSeconds(1800)), clock);
        gate = sessions.identityGateHash();
        admin = new PersistentAdminService(sessions, jdbc, manager, new AdminCrypto(keys[3], keys[4], "test-key", random),
                new AdminPolicy(Duration.ofSeconds(900), Duration.ofSeconds(3600), 5, Duration.ofSeconds(120), Duration.ofSeconds(300)), clock);
        admissionCrypto = new AdmissionCrypto(keys[5], keys[6], "test-key", random);
        store = new PrivateFileStore(storage, keys[7], keys[8], "test-key", random);
        photos = new VehiclePhotoService(sessions, jdbc, store, admin);
        v1 = stack(DocumentCatalog.V1);
        established = stack(ALL_ESTABLISHED);
        current = stack(DocumentCatalog.CURRENT);
        BufferedImage image = new BufferedImage(640, 480, BufferedImage.TYPE_INT_RGB);
        var out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpeg", out);
        jpeg = out.toByteArray();
        nextYear = LocalDate.ofInstant(clock.instant(), ZoneId.of("Africa/Douala")).plusYears(1);
    }

    private Stack stack(List<DocumentCatalog.Requirement> catalog) {
        var applications = new PersistentDriverApplicationService(sessions, jdbc, manager, admissionCrypto, Duration.ofSeconds(600), clock, catalog);
        return new Stack(applications, new DriverDocumentService(sessions, applications, jdbc, store, admin),
                new AdmissionReviewService(sessions, admin, applications, jdbc, admissionCrypto));
    }

    @AfterEach void cleanOnlyRandomNamespacesAndRespectForeignKeys() {
        try {
            if (jdbc != null && gate != null) {
                jdbc.update("DELETE FROM audit_event WHERE correlation_ref IN (SELECT id FROM admin_session WHERE gate_hash=?)", gate);
                for (String table : List.of("ledger_entry", "withdrawal", "payment", "subscription_period", "driver_trial", "ride_reservation", "ride_proposal", "ride_search", "quote", "driver_presence", "referral_attempt", "referral_link", "referral_code", "voyage_reservation", "voyage_price", "voyage_stop", "voyage", "admission_waiver", "admission_document_review", "admission_photo_review", "admission_decision", "driver_document",
                        "vehicle_photo", "driver_application_command", "driver_application", "admin_session", "admin_totp", "admin_grant",
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
            if (keys != null) for (byte[] key : keys) Arrays.fill(key, (byte) 0);
            if (gateway != null && gateway.code != null) Arrays.fill(gateway.code, '\0');
        }
    }

    @Test void eachRouteShowsAndAcceptsOnlyItsOwnDocuments() {
        Login candidate = login(candidatePhone);
        UUID taxi = v1.applications.create(candidate.token, client, UUID.randomUUID(), "taxi").applicationId();
        assertFile(FileException.Code.VEHICLE_REQUIRED, () -> upload(v1, candidate, taxi, "REGISTRATION_CERTIFICATE"));
        v1.applications.updateVehicle(candidate.token, client, taxi, UUID.randomUUID(), 1, car("holder", "AB 12-CD"));
        assertEquals(List.of("IDENTITY", "DRIVING_LICENCE", "REGISTRATION_CERTIFICATE", "INSURANCE", "TECHNICAL_INSPECTION",
                "TRANSPORT_LICENCE", "PUBLIC_TRANSPORT_CARD"), codes(v1.applications.requirements(candidate.token, client, taxi)));
        assertFile(FileException.Code.NOT_APPLICABLE, () -> upload(v1, candidate, taxi, "PAID_TRANSPORT_AUTHORIZATION"));
        assertFile(FileException.Code.NOT_APPLICABLE, () -> upload(v1, candidate, taxi, "USE_AUTHORIZATION"));
        assertFile(FileException.Code.EXPIRED, () -> v1.documents.upload(candidate.token, client, taxi, "INSURANCE",
                UUID.randomUUID(), "image/jpeg", jpeg, LocalDate.of(2020, 1, 1)));
        for (String view : AdmissionReviewService.VIEWS) photos.upload(candidate.token, client, taxi, view, UUID.randomUUID(), "image/jpeg", jpeg);
        assertAdmission(AdmissionException.Code.INCOMPLETE, () -> v1.applications.submit(candidate.token, client, taxi, UUID.randomUUID(), 2));
        upload(v1, candidate, taxi, "IDENTITY");
        assertAdmission(AdmissionException.Code.INCOMPLETE, () -> v1.applications.submit(candidate.token, client, taxi, UUID.randomUUID(), 2),
                "identity and licence are always required to send the file");
        // Since catalogue v2, vehicle documents may be missing at submission: the agent judges or waives them.
        upload(v1, candidate, taxi, "DRIVING_LICENCE");
        // Requirements still to be confirmed are shown, optional for submission and never satisfied.
        var statuses = v1.applications.requirements(candidate.token, client, taxi);
        assertTrue(statuses.stream().filter(s -> "to_confirm".equals(s.status())).noneMatch(RequirementStatus::requiredForSubmission));
        assertTrue(statuses.stream().noneMatch(RequirementStatus::satisfied), "received is never accepted");
        assertEquals("submitted", v1.applications.submit(candidate.token, client, taxi, UUID.randomUUID(), 2).state());

        clock.advance(61);
        Login other = login(thirdPhone);
        UUID personal = v1.applications.create(other.token, client, UUID.randomUUID(), "private").applicationId();
        v1.applications.updateVehicle(other.token, client, personal, UUID.randomUUID(), 1, car("authorized_use", "XY 99-ZZ"));
        assertEquals(List.of("IDENTITY", "DRIVING_LICENCE", "REGISTRATION_CERTIFICATE", "INSURANCE", "TECHNICAL_INSPECTION",
                "USE_AUTHORIZATION", "PAID_TRANSPORT_AUTHORIZATION"), codes(v1.applications.requirements(other.token, client, personal)));
        assertFile(FileException.Code.NOT_APPLICABLE, () -> upload(v1, other, personal, "TRANSPORT_LICENCE"));
        assertFile(FileException.Code.NOT_APPLICABLE, () -> upload(v1, other, personal, "PUBLIC_TRANSPORT_CARD"));
        assertEquals("received", upload(v1, other, personal, "USE_AUTHORIZATION").reviewState());
        assertFile(FileException.Code.NOT_FOUND, () -> upload(v1, other, taxi, "IDENTITY"));
    }

    @Test void catalogueV1KeepsApprovalBlockedWhileRequirementsAreToConfirm() {
        Login candidate = login(candidatePhone);
        UUID app = readyFile(v1, candidate, "taxi", "holder", "AB 12-CD", false);
        clock.advance(61);
        Login agent = login(agentPhone);
        char[] adminToken = adminSession(agent);
        var detail = v1.reviews.detail(agent.token, client, adminToken, app);
        assertTrue(detail.requirements().stream().anyMatch(r -> "to_confirm".equals(r.status())));
        assertAdmission(AdmissionException.Code.APPROVAL_NOT_AVAILABLE, () -> v1.reviews.decide(agent.token, client, adminToken, app,
                UUID.randomUUID(), detail.application().version(), "approve", "ALL_CONDITIONS_MET", null,
                allPhotos(detail, "acceptable"), allDocuments(detail, "acceptable")));
        assertEquals("submitted", v1.applications.read(candidate.token, client, app).state());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM driver_document WHERE gate_hash=? AND review_state<>'received'", Integer.class, gate),
                "a refused approval changes no document");
    }

    @Test void correctionTargetsOnlyThePieceConcernedThenApprovalWithEstablishedRequirements() {
        Login candidate = login(candidatePhone);
        UUID app = readyFile(established, candidate, "private", "authorized_use", "XY 99-ZZ", true);
        clock.advance(61);
        Login agent = login(agentPhone);
        char[] adminToken = adminSession(agent);
        var detail = established.reviews.detail(agent.token, client, adminToken, app);
        UUID insurance = documentOf(detail.requirements(), "INSURANCE");
        assertTrue(established.documents.examinationContent(agent.token, client, adminToken, insurance).jpeg().length > 0);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE object_ref=? AND object_type='driver_document'",
                Integer.class, insurance.toString()));
        var reviews = new ArrayList<AdmissionReviewService.DocumentReview>();
        for (var requirement : detail.requirements()) {
            boolean target = requirement.code().equals("INSURANCE");
            reviews.add(new AdmissionReviewService.DocumentReview(requirement.documentId(), null,
                    target ? "to_correct" : "acceptable", target ? "COVER_NOT_SHOWN" : null));
        }
        assertAdmission(AdmissionException.Code.INCOMPLETE, () -> established.reviews.decide(agent.token, client, adminToken, app,
                UUID.randomUUID(), detail.application().version(), "approve", "ALL_CONDITIONS_MET", null, List.of(), allDocuments(detail, "acceptable")),
                "approval needs all six photos acceptable");
        var correction = established.reviews.decide(agent.token, client, adminToken, app, UUID.randomUUID(), detail.application().version(),
                "request_correction", "DOCUMENT_TO_REPLACE", "L'attestation doit mentionner le transport de passagers.",
                allPhotos(detail, "acceptable"), reviews);
        assertEquals("INSURANCE", correction.documentReviews().stream().filter(r -> "to_correct".equals(r.result())).findFirst().orElseThrow().documentType());

        var mine = established.applications.requirements(candidate.token, client, app);
        assertEquals("to_correct", statusOf(mine, "INSURANCE").reviewState());
        assertTrue(mine.stream().filter(s -> !s.code().equals("INSURANCE")).allMatch(RequirementStatus::satisfied),
                "every other piece stays accepted");
        long version = established.applications.readCurrent(candidate.token, client).version();
        assertAdmission(AdmissionException.Code.INCOMPLETE, () -> established.applications.submit(candidate.token, client, app, UUID.randomUUID(), version),
                "the piece to correct must be replaced first");
        UUID newInsurance = upload(established, candidate, app, "INSURANCE").documentId();
        long resubmitted = established.applications.submit(candidate.token, client, app, UUID.randomUUID(), version).version();

        var access = new com.waka.drivers.DriverAccessService(sessions, established.applications);
        assertEquals("none", access.access(candidate.token, client).access(), "submitted is not approved");
        var again = established.reviews.detail(agent.token, client, adminToken, app);
        assertEquals(newInsurance, documentOf(again.requirements(), "INSURANCE"));
        var approval = established.reviews.decide(agent.token, client, adminToken, app, UUID.randomUUID(), resubmitted, "approve",
                "ALL_CONDITIONS_MET", null, allPhotos(again, "acceptable"),
                List.of(new AdmissionReviewService.DocumentReview(newInsurance, null, "acceptable", null)));
        assertEquals("approve", approval.action());
        assertEquals("approved", established.applications.read(candidate.token, client, app).state());
        assertEquals("test", jdbc.queryForObject("SELECT catalogue_version FROM admission_decision WHERE gate_hash=? AND action='approve'", String.class, gate));
        var granted = access.access(candidate.token, client);
        assertEquals("provisional", granted.access(), "approved private driver before a first service");
        assertEquals("not_started", granted.trial());
        jdbc.update("UPDATE driver_document SET valid_until=? WHERE gate_hash=? AND id=?", java.time.LocalDate.of(2020, 1, 1), gate, newInsurance.toString());
        var blocked = access.access(candidate.token, client);
        assertEquals("new_reservations_blocked", blocked.access(), "an expired essential document blocks new reservations");
        assertEquals(List.of("INSURANCE"), blocked.documentsToRenew());
    }

    @Test void catalogueV2LetsAnAgentApproveAnIncompleteFileWithTracedWaiversButNeverForIdentityOrLicence() {
        Login candidate = login(candidatePhone);
        UUID app = readyFile(current, candidate, "taxi", "holder", "CD 34-EF", false);
        assertEquals("submitted", current.applications.read(candidate.token, client, app).state(), "sent with identity and licence only");
        clock.advance(61);
        Login agent = login(agentPhone);
        char[] adminToken = adminSession(agent);
        var detail = current.reviews.detail(agent.token, client, adminToken, app);
        long version = detail.application().version();
        var personDocuments = allDocuments(detail, "acceptable");
        assertAdmission(AdmissionException.Code.INCOMPLETE, () -> current.reviews.decide(agent.token, client, adminToken, app,
                UUID.randomUUID(), version, "approve", "ALL_CONDITIONS_MET", null, allPhotos(detail, "acceptable"), personDocuments, List.of()),
                "missing vehicle documents need an explicit waiver");
        assertAdmission(AdmissionException.Code.INVALID_REQUEST, () -> current.reviews.decide(agent.token, client, adminToken, app,
                UUID.randomUUID(), version, "approve", "ALL_CONDITIONS_MET", null, allPhotos(detail, "acceptable"), personDocuments,
                List.of(new AdmissionReviewService.Waiver("IDENTITY", "FOUNDER_POLICY"))), "identity is never waived");
        assertThrows(AdmissionException.class, () -> current.reviews.decide(agent.token, client, adminToken, app,
                UUID.randomUUID(), version, "request_correction", "DOCUMENT_TO_REPLACE", null, List.of(), List.of(),
                List.of(new AdmissionReviewService.Waiver("INSURANCE", "LATER"))), "a waiver only goes with an approval");
        var waivers = new ArrayList<AdmissionReviewService.Waiver>();
        for (String code : List.of("REGISTRATION_CERTIFICATE", "INSURANCE", "TECHNICAL_INSPECTION", "TRANSPORT_LICENCE", "PUBLIC_TRANSPORT_CARD")) {
            waivers.add(new AdmissionReviewService.Waiver(code, "TO_PROVIDE_AFTER_START"));
        }
        var approval = current.reviews.decide(agent.token, client, adminToken, app, UUID.randomUUID(), version, "approve",
                "ALL_CONDITIONS_MET", null, allPhotos(detail, "acceptable"), personDocuments, waivers);
        assertEquals(5, approval.waivers().size());
        assertEquals("approved", current.applications.read(candidate.token, client, app).state());
        assertEquals("v2", jdbc.queryForObject("SELECT catalogue_version FROM admission_decision WHERE gate_hash=? AND action='approve'", String.class, gate));
        assertEquals(5, jdbc.queryForObject("SELECT COUNT(*) FROM admission_waiver WHERE gate_hash=?", Integer.class, gate));
        var access = new com.waka.drivers.DriverAccessService(sessions, current.applications).access(candidate.token, client);
        assertEquals("active", access.access(), "waived documents do not block the taxi");
        var mine = current.applications.requirements(candidate.token, client, app);
        assertNull(statusOf(mine, "PUBLIC_TRANSPORT_CARD").documentId(), "a waived document stays to provide");
        assertEquals(5, current.reviews.candidateDecisions(candidate.token, client, app).getLast().waivers().size());

        // R11: the approved driver publishes an intercity voyage, no subscription needed.
        byte[] voyageEnc = new byte[32], voyageMac = new byte[32];
        new SecureRandom().nextBytes(voyageEnc); new SecureRandom().nextBytes(voyageMac);
        var voyages = new com.waka.mobility.voyage.PersistentVoyageService(sessions, jdbc, current.applications,
                new com.waka.identity.profile.ProfileCrypto(voyageEnc, voyageMac, "test-key", new SecureRandom()), clock);
        Instant start = clock.instant().plus(Duration.ofDays(2));
        var stops = List.of(new com.waka.mobility.voyage.PersistentVoyageService.Stop("Yaoundé, gare de Mvan", start),
                new com.waka.mobility.voyage.PersistentVoyageService.Stop("Edéa", start.plus(Duration.ofHours(3))),
                new com.waka.mobility.voyage.PersistentVoyageService.Stop("Douala, Akwa", start.plus(Duration.ofHours(4))));
        var prices = java.util.Map.of("0-1", 4000, "0-2", 6000, "1-2", 2000);
        UUID voyageKey = UUID.randomUUID();
        assertEquals(com.waka.mobility.voyage.VoyageException.Code.TOO_MANY_SEATS, assertThrows(com.waka.mobility.voyage.VoyageException.class,
                () -> voyages.publish(candidate.token, client, UUID.randomUUID(), stops, 5, prices)).code(), "the car offers 4 seats");
        var published = voyages.publish(candidate.token, client, voyageKey, stops, 4, prices);
        assertEquals("published", published.state());
        assertEquals(6000, published.prices().get("0-2"));
        assertEquals(published.voyageId(), voyages.publish(candidate.token, client, voyageKey, stops, 4, prices).voyageId(), "same key, same voyage");
        assertEquals(com.waka.mobility.voyage.VoyageException.Code.IDEMPOTENCY_KEY_REUSED, assertThrows(com.waka.mobility.voyage.VoyageException.class,
                () -> voyages.publish(candidate.token, client, voyageKey, stops, 3, prices)).code());
        assertEquals(com.waka.mobility.voyage.VoyageException.Code.DRIVER_NOT_ALLOWED, assertThrows(com.waka.mobility.voyage.VoyageException.class,
                () -> voyages.publish(agent.token, client, UUID.randomUUID(), stops, 1, prices)).code(), "no approved file");
        var listed = voyages.upcoming(agent.token, client);
        assertEquals(List.of(published.voyageId()), listed.stream().map(com.waka.mobility.voyage.PersistentVoyageService.VoyageView::voyageId).toList());
        assertEquals("Edéa", listed.getFirst().stops().get(1).place());
        // Seats are counted per leg (0: Yaoundé -> Edéa, 1: Edéa -> Douala); the car offers 4.
        java.util.function.Function<Runnable, com.waka.mobility.voyage.VoyageException.Code> code =
                action -> assertThrows(com.waka.mobility.voyage.VoyageException.class, action::run).code();
        assertEquals(com.waka.mobility.voyage.VoyageException.Code.OWN_VOYAGE,
                code.apply(() -> voyages.reserve(candidate.token, client, UUID.randomUUID(), published.voyageId(), 0, 2, 1)));
        UUID firstKey = UUID.randomUUID();
        var first = voyages.reserve(agent.token, client, firstKey, published.voyageId(), 0, 1, 2);
        assertEquals("awaiting_payment", first.state());
        assertEquals(8000, first.totalXaf(), "2 seats x 4 000 F from the driver's list");
        assertEquals(first.reservationId(), voyages.reserve(agent.token, client, firstKey, published.voyageId(), 0, 1, 2).reservationId());
        var second = voyages.reserve(agent.token, client, UUID.randomUUID(), published.voyageId(), 1, 2, 4);
        assertEquals(java.util.Map.of("0-1", 2, "0-2", 0, "1-2", 0), voyages.upcoming(agent.token, client).getFirst().availableSeats());
        assertEquals(com.waka.mobility.voyage.VoyageException.Code.NOT_ENOUGH_SEATS,
                code.apply(() -> voyages.reserve(agent.token, client, UUID.randomUUID(), published.voyageId(), 0, 2, 1)), "leg 1 is full");
        assertEquals("cancelled_by_client", voyages.cancelReservation(agent.token, client, second.reservationId()).state());
        assertNull(voyages.myReservations(agent.token, client).stream().filter(r -> r.reservationId().equals(second.reservationId()))
                .findFirst().orElseThrow().refundXaf(), "never paid: nothing to refund");
        // Payment does not exist yet: a confirmed reservation is simulated to check the R11 settlement.
        jdbc.update("UPDATE voyage_reservation SET state='confirmed' WHERE gate_hash=? AND id=?", gate, first.reservationId().toString());
        clock.advance(Duration.ofDays(2).minus(Duration.ofHours(5)).toSeconds());
        // Sessions last at most two hours here: both people log in again.
        Login rider = login(agentPhone);
        clock.advance(61);
        Login driver = login(candidatePhone);
        var cancelled = voyages.cancelReservation(rider.token, client, first.reservationId());
        assertEquals(15, cancelled.feePercent(), "5 h before departure");
        assertEquals(6800, cancelled.refundXaf());
        assertEquals(600, cancelled.driverXaf());
        assertEquals(600, cancelled.wakaXaf());
        assertEquals(cancelled.refundXaf(), voyages.cancelReservation(rider.token, client, first.reservationId()).refundXaf(), "repeat changes nothing");
        var held = voyages.reserve(rider.token, client, UUID.randomUUID(), published.voyageId(), 0, 2, 3);
        clock.advance(181);
        assertEquals("expired", voyages.myReservations(rider.token, client).getFirst().state(), "payment window over");
        assertEquals(4, voyages.upcoming(rider.token, client).getFirst().availableSeats().get("0-2"), "expired holds free their seats");
        var paid = voyages.reserve(rider.token, client, UUID.randomUUID(), published.voyageId(), 0, 2, 1);
        jdbc.update("UPDATE voyage_reservation SET state='confirmed' WHERE gate_hash=? AND id=?", gate, paid.reservationId().toString());
        assertNotNull(held);

        // Boarding: the driver sees sections and seats only, and boards with the code shown to the client.
        var boardedOne = voyages.reserve(rider.token, client, UUID.randomUUID(), published.voyageId(), 0, 2, 1);
        jdbc.update("UPDATE voyage_reservation SET state='confirmed' WHERE gate_hash=? AND id=?", gate, boardedOne.reservationId().toString());
        var unpaid = voyages.reserve(rider.token, client, UUID.randomUUID(), published.voyageId(), 1, 2, 1);
        String boardingCode = voyages.myReservations(rider.token, client).stream()
                .filter(r -> r.reservationId().equals(boardedOne.reservationId())).findFirst().orElseThrow().boardingCode();
        assertTrue(boardingCode.matches("[0-9]{6}"));
        var passengers = voyages.passengers(driver.token, client, published.voyageId());
        assertEquals(3, passengers.size(), "two paid, one awaiting payment; the expired hold is gone");
        assertEquals(com.waka.mobility.voyage.VoyageException.Code.NOT_FOUND,
                code.apply(() -> voyages.passengers(rider.token, client, published.voyageId())), "only the driver sees them");
        String wrong = boardingCode.equals("000000") ? "000001" : "000000";
        assertEquals(com.waka.mobility.voyage.VoyageException.Code.WRONG_CODE,
                code.apply(() -> voyages.board(driver.token, client, published.voyageId(), wrong)));
        assertEquals(com.waka.mobility.voyage.VoyageException.Code.NOT_PAID, code.apply(() -> voyages.board(driver.token, client,
                published.voyageId(), voyages.myReservations(rider.token, client).stream()
                        .filter(r -> r.reservationId().equals(unpaid.reservationId())).findFirst().orElseThrow().boardingCode())));
        var boarded = voyages.board(driver.token, client, published.voyageId(), boardingCode);
        assertEquals("boarded", boarded.state());
        assertEquals(5400, boarded.driverXaf(), "6 000 F minus 10 % at boarding");
        assertEquals(boarded.reservationId(), voyages.board(driver.token, client, published.voyageId(), boardingCode).reservationId(), "same code again");
        assertNull(voyages.myReservations(rider.token, client).stream().filter(r -> r.reservationId().equals(boardedOne.reservationId()))
                .findFirst().orElseThrow().boardingCode(), "a used code is no longer shown");
        for (int i = 0; i < 9; i++) {
            assertEquals(com.waka.mobility.voyage.VoyageException.Code.WRONG_CODE,
                    code.apply(() -> voyages.board(driver.token, client, published.voyageId(), wrong)));
        }
        assertEquals(com.waka.mobility.voyage.VoyageException.Code.BOARDING_LOCKED,
                code.apply(() -> voyages.board(driver.token, client, published.voyageId(), boardingCode)), "ten wrong codes lock boarding");
        assertEquals(2, voyages.upcoming(rider.token, client).getFirst().availableSeats().get("0-1"),
                "a boarded passenger keeps the seat (boarded + paid on 0-2 out of 4)");

        // R12: once the rider took part in a completed paid service, no referrer can be attached any more.
        var referrals = new com.waka.referrals.PersistentReferralService(sessions, jdbc, new SecureRandom(), clock);
        String driverCode = referrals.mine(driver.token, client).referralCode();
        assertEquals(com.waka.referrals.ReferralException.Code.WINDOW_CLOSED, assertThrows(com.waka.referrals.ReferralException.class,
                () -> referrals.attach(rider.token, client, driverCode)).code());

        // R12: a paid passenger absent after the boarding time is settled like a late cancellation (30 %).
        var absent = voyages.reserve(rider.token, client, UUID.randomUUID(), published.voyageId(), 0, 2, 1);
        jdbc.update("UPDATE voyage_reservation SET state='confirmed' WHERE gate_hash=? AND id=?", gate, absent.reservationId().toString());
        assertEquals(com.waka.mobility.voyage.VoyageException.Code.TOO_EARLY,
                code.apply(() -> voyages.markNoShow(driver.token, client, published.voyageId(), absent.reservationId())));
        // The boarding time is moved into the past in the test data instead of waiting for it.
        jdbc.update("UPDATE voyage_stop SET planned_at=? WHERE gate_hash=? AND voyage_id=? AND stop_index=0",
                java.time.LocalDateTime.ofInstant(clock.instant().minusSeconds(60), java.time.ZoneOffset.UTC), gate, published.voyageId().toString());
        var noShow = voyages.markNoShow(driver.token, client, published.voyageId(), absent.reservationId());
        assertEquals("no_show", noShow.state());
        assertEquals(900, noShow.driverXaf(), "30 % of 6 000 F = 1 800 F, half to the driver");
        assertEquals(4200, voyages.myReservations(rider.token, client).stream().filter(r -> r.reservationId().equals(absent.reservationId()))
                .findFirst().orElseThrow().refundXaf());
        assertEquals("no_show", voyages.markNoShow(driver.token, client, published.voyageId(), absent.reservationId()).state(), "repeat");
        assertEquals(com.waka.mobility.voyage.VoyageException.Code.NOT_FOUND, assertThrows(com.waka.mobility.voyage.VoyageException.class,
                () -> voyages.cancel(rider.token, client, published.voyageId(), "CHANGE_OF_PLAN")).code(), "only its driver cancels");
        assertEquals("cancelled", voyages.cancel(driver.token, client, published.voyageId(), "CHANGE_OF_PLAN").state());
        var refunded = voyages.myReservations(rider.token, client).stream().filter(r -> r.reservationId().equals(paid.reservationId())).findFirst().orElseThrow();
        assertEquals("cancelled_by_driver", refunded.state());
        assertEquals(6000, refunded.refundXaf(), "driver cancellation: full refund");
        assertTrue(voyages.upcoming(rider.token, client).isEmpty());
        assertEquals(1, voyages.mine(driver.token, client).size());
    }
    @Test void cityRideFromQuoteToEndWithOneProposalAtATimeAndOneActiveRidePerCar() {
        Login driver = login(candidatePhone);
        UUID app = readyFile(current, driver, "taxi", "holder", "RS 11-AB", false);
        clock.advance(61);
        Login agent = login(agentPhone);
        char[] adminToken = adminSession(agent);
        var detail = current.reviews.detail(agent.token, client, adminToken, app);
        var waivers = new ArrayList<AdmissionReviewService.Waiver>();
        for (String code : List.of("REGISTRATION_CERTIFICATE", "INSURANCE", "TECHNICAL_INSPECTION", "TRANSPORT_LICENCE", "PUBLIC_TRANSPORT_CARD")) {
            waivers.add(new AdmissionReviewService.Waiver(code, "TO_PROVIDE_AFTER_START"));
        }
        current.reviews.decide(agent.token, client, adminToken, app, UUID.randomUUID(), detail.application().version(), "approve",
                "ALL_CONDITIONS_MET", null, allPhotos(detail, "acceptable"), allDocuments(detail, "acceptable"), waivers);
        clock.advance(61);
        Login rider = login(thirdPhone);
        SecureRandom random = new SecureRandom();
        byte[] e1 = new byte[32], m1 = new byte[32], e2 = new byte[32], m2 = new byte[32];
        for (byte[] k : new byte[][] { e1, m1, e2, m2 }) random.nextBytes(k);
        var quoteCrypto = new com.waka.identity.profile.ProfileCrypto(e1, m1, "test-key", random);
        var fares = new com.waka.pricing.infrastructure.JdbcFareScheduleRepository(jdbc);
        var road = new com.waka.geo.RouteDistanceProvider() {
            @Override public java.math.BigDecimal roadDistanceMetres(com.waka.geo.GeoPoint a, com.waka.geo.GeoPoint b) { return new java.math.BigDecimal("7480"); }
            @Override public RoadRoute roadRoute(com.waka.geo.GeoPoint a, com.waka.geo.GeoPoint b) {
                return new RoadRoute(roadDistanceMetres(a, b), com.waka.geo.Polylines.encode(java.util.List.of(a, b)));
            }
        };
        var quotes = new com.waka.mobility.quote.PersistentQuoteService(sessions, jdbc, quoteCrypto, fares, "launch-v1",
                road, com.waka.geo.CircleServiceArea.LAUNCH, clock);
        java.util.concurrent.atomic.AtomicInteger pickup = new java.util.concurrent.atomic.AtomicInteger(420);
        var rides = new com.waka.mobility.ride.PersistentRideService(sessions, jdbc, current.applications, quotes, fares,
                (from, to) -> pickup.get(), new com.waka.identity.profile.ProfileCrypto(e2, m2, "test-key", random), clock);
        java.util.function.Function<Runnable, com.waka.mobility.ride.RideException.Code> code =
                action -> assertThrows(com.waka.mobility.ride.RideException.class, action::run).code();
        var centre = new com.waka.geo.GeoPoint(new java.math.BigDecimal("3.848"), new java.math.BigDecimal("11.502"));
        var there = new com.waka.geo.GeoPoint(new java.math.BigDecimal("3.87"), new java.math.BigDecimal("11.52"));

        assertEquals(com.waka.mobility.ride.RideException.Code.DRIVER_NOT_ALLOWED,
                code.apply(() -> rides.presence(rider.token, client, true, centre)), "a client without approval cannot go online");
        rides.presence(driver.token, client, true, centre);
        var quote = quotes.create(rider.token, client, UUID.randomUUID(), com.waka.mobility.quote.PersistentQuoteService.Mode.SHARED, 2, centre, there);
        assertEquals(1200, quote.totalXaf(), "2 seats x 600 F");
        assertNotNull(quote.route(), "blue: the trip's shape comes with the price, from the same maps call");
        pickup.set(900);
        var tooFar = rides.search(rider.token, client, UUID.randomUUID(), quote.quoteId());
        assertFalse(tooFar.proposalPending(), "15 minutes away: above the 10-minute pickup target");
        pickup.set(420);
        var search = rides.readSearch(rider.token, client, tooFar.searchId());
        assertTrue(search.proposalPending());
        var proposal = rides.proposals(driver.token, client).getFirst();
        assertEquals(1200, proposal.priceXaf());
        assertEquals(1080, proposal.driverXaf(), "R03: commission 120 F on 2 x 600 F");
        assertEquals(420, proposal.pickupSeconds());
        var ride = rides.respond(driver.token, client, proposal.proposalId(), true);
        assertEquals("awaiting_payment", ride.state());
        assertEquals("matched", rides.readSearch(rider.token, client, search.searchId()).state());
        String boardingCode = rides.readSearch(rider.token, client, search.searchId()).ride().boardingCode();
        assertTrue(boardingCode.matches("[0-9]{6}"));
        assertNull(rides.ride(driver.token, client, ride.reservationId()).boardingCode(), "the driver never sees the code");
        // K36: the client follows the driver coming; the driver's own view carries no position; both find the ride again.
        var followed = rides.ride(rider.token, client, ride.reservationId());
        assertEquals(centre, followed.driverPosition());
        assertNotNull(followed.driverPositionAt());
        assertNull(rides.ride(driver.token, client, ride.reservationId()).driverPosition());
        assertEquals(quote.route(), followed.route(), "the client keeps seeing his trip in blue, without a new maps call");
        assertEquals(ride.reservationId(), rides.activeRide(rider.token, client).reservationId());
        assertEquals("driver", rides.activeRide(driver.token, client).viewerRole());
        assertNull(rides.activeRide(agent.token, client), "nothing in progress for a third account");
        clock.advance(61);
        assertNull(rides.ride(rider.token, client, ride.reservationId()).driverPosition(), "a position older than a minute is not shown");
        rides.presence(driver.token, client, true, centre);

        // One active ride per car: a second quote finds no free car.
        var second = quotes.create(rider.token, client, UUID.randomUUID(), com.waka.mobility.quote.PersistentQuoteService.Mode.VIP, 1, centre, there);
        assertFalse(rides.search(rider.token, client, UUID.randomUUID(), second.quoteId()).proposalPending());
        assertEquals(com.waka.mobility.ride.RideException.Code.NOT_PAID,
                code.apply(() -> rides.driverAction(driver.token, client, ride.reservationId(), "arrive", null)));
        jdbc.update("UPDATE ride_reservation SET state='confirmed' WHERE gate_hash=? AND id=?", gate, ride.reservationId().toString());
        rides.driverAction(driver.token, client, ride.reservationId(), "arrive", null);
        assertEquals(com.waka.mobility.ride.RideException.Code.TOO_EARLY,
                code.apply(() -> rides.driverAction(driver.token, client, ride.reservationId(), "client_absent", null)), "five minutes of waiting first");
        String wrong = boardingCode.equals("000000") ? "000001" : "000000";
        assertEquals(com.waka.mobility.ride.RideException.Code.WRONG_CODE,
                code.apply(() -> rides.driverAction(driver.token, client, ride.reservationId(), "board", wrong)));
        assertEquals("boarded", rides.driverAction(driver.token, client, ride.reservationId(), "board", boardingCode).state());
        assertEquals(com.waka.mobility.ride.RideException.Code.ALREADY_BOARDED,
                code.apply(() -> rides.cancelByClient(rider.token, client, ride.reservationId())));
        assertNull(rides.ride(rider.token, client, ride.reservationId()).driverPosition(), "on board: no more following");
        var finished = rides.driverAction(driver.token, client, ride.reservationId(), "finish", null);
        assertEquals("finished", finished.state());
        assertEquals(1080, finished.driverXaf());
        assertNull(rides.activeRide(rider.token, client), "a finished ride is no longer in progress");

        // The car is free again; a client cancellation before boarding is fully refunded (R08).
        var third = quotes.create(rider.token, client, UUID.randomUUID(), com.waka.mobility.quote.PersistentQuoteService.Mode.VIP, 1, centre, there);
        var s3 = rides.search(rider.token, client, UUID.randomUUID(), third.quoteId());
        assertTrue(s3.proposalPending());
        var p3 = rides.proposals(driver.token, client).getFirst();
        assertEquals(7500, p3.priceXaf(), "VIP at 600 F a seat");
        var r3 = rides.respond(driver.token, client, p3.proposalId(), true);
        jdbc.update("UPDATE ride_reservation SET state='confirmed' WHERE gate_hash=? AND id=?", gate, r3.reservationId().toString());
        var cancelled = rides.cancelByClient(rider.token, client, r3.reservationId());
        assertEquals("cancelled_by_client", cancelled.state());
        assertEquals(7500L, cancelled.refundXaf());
        assertEquals(com.waka.mobility.ride.RideException.Code.NOT_FOUND,
                code.apply(() -> rides.ride(agent.token, client, r3.reservationId())), "only its two parties see a ride");
    }

    @Test void sharedRidesByDirectionGroupClientsWithinSeatsAndFiveMinutesOfDetour() {
        Login driver = login(candidatePhone);
        UUID app = readyFile(current, driver, "taxi", "holder", "RS 33-EF", false);
        clock.advance(61);
        Login agent = login(agentPhone);
        char[] adminToken = adminSession(agent);
        var detail = current.reviews.detail(agent.token, client, adminToken, app);
        var waivers = new ArrayList<AdmissionReviewService.Waiver>();
        for (String c : List.of("REGISTRATION_CERTIFICATE", "INSURANCE", "TECHNICAL_INSPECTION", "TRANSPORT_LICENCE", "PUBLIC_TRANSPORT_CARD")) {
            waivers.add(new AdmissionReviewService.Waiver(c, "TO_PROVIDE_AFTER_START"));
        }
        current.reviews.decide(agent.token, client, adminToken, app, UUID.randomUUID(), detail.application().version(), "approve",
                "ALL_CONDITIONS_MET", null, allPhotos(detail, "acceptable"), allDocuments(detail, "acceptable"), waivers);
        clock.advance(61);
        Login rider = login(thirdPhone);
        SecureRandom random = new SecureRandom();
        byte[] e1 = new byte[32], m1 = new byte[32], e2 = new byte[32], m2 = new byte[32];
        for (byte[] k : new byte[][] { e1, m1, e2, m2 }) random.nextBytes(k);
        var quotes = new com.waka.mobility.quote.PersistentQuoteService(sessions, jdbc, new com.waka.identity.profile.ProfileCrypto(e1, m1, "test-key", random),
                new com.waka.pricing.infrastructure.JdbcFareScheduleRepository(jdbc), "launch-v1", (a, b) -> new java.math.BigDecimal("7480"),
                com.waka.geo.CircleServiceArea.LAUNCH, clock);
        // Every road leg takes 60 s: a client on the way adds 60 + 60 + 60 - 60 = 120 s of detour.
        java.util.concurrent.atomic.AtomicInteger leg = new java.util.concurrent.atomic.AtomicInteger(60);
        var rides = new com.waka.mobility.ride.PersistentRideService(sessions, jdbc, current.applications, quotes,
                new com.waka.pricing.infrastructure.JdbcFareScheduleRepository(jdbc), (from, to) -> leg.get(),
                new com.waka.identity.profile.ProfileCrypto(e2, m2, "test-key", random), clock);
        var a = new com.waka.geo.GeoPoint(new java.math.BigDecimal("3.848"), new java.math.BigDecimal("11.502"));
        var b = new com.waka.geo.GeoPoint(new java.math.BigDecimal("3.87"), new java.math.BigDecimal("11.52"));
        var mvan = new com.waka.geo.GeoPoint(new java.math.BigDecimal("3.827"), new java.math.BigDecimal("11.515"));
        java.util.function.Function<com.waka.mobility.quote.PersistentQuoteService.Mode, Boolean> offered = m -> {
            var q = quotes.create(rider.token, client, UUID.randomUUID(), m, 1, a, b);
            return rides.search(rider.token, client, UUID.randomUUID(), q.quoteId()).proposalPending();
        };

        var presence = rides.presence(driver.token, client, true, a, mvan);
        assertEquals(mvan, presence.destination());
        assertNotNull(presence.route(), "green: his own route, as soon as he gives a direction");
        assertNull(rides.presence(driver.token, client, true, a).route(), "no direction, no route");
        presence = rides.presence(driver.token, client, true, a, mvan);
        assertFalse(offered.apply(com.waka.mobility.quote.PersistentQuoteService.Mode.VIP), "with a direction: never a VIP");
        assertTrue(offered.apply(com.waka.mobility.quote.PersistentQuoteService.Mode.SHARED));
        var p1 = rides.proposals(driver.token, client).getFirst();
        assertEquals(120, p1.detourSeconds());
        var shapes = rides.proposalPaths(driver.token, client, p1.proposalId());
        assertNotNull(shapes.normal(), "green: his own route to his direction");
        assertNotNull(shapes.client(), "blue: the client's trip");
        assertNotNull(shapes.combined(), "yellow: his route with this client");
        assertSame(shapes, rides.proposalPaths(driver.token, client, p1.proposalId()), "asked once, then reused");
        assertEquals(com.waka.mobility.ride.RideException.Code.NOT_FOUND, assertThrows(com.waka.mobility.ride.RideException.class,
                () -> rides.proposalPaths(rider.token, client, p1.proposalId())).code(), "only the driver it was sent to");
        var r1 = rides.respond(driver.token, client, p1.proposalId(), true);
        assertTrue(offered.apply(com.waka.mobility.quote.PersistentQuoteService.Mode.SHARED), "a second stranger on the way, the car still has seats");
        var r2 = rides.respond(driver.token, client, rides.proposals(driver.token, client).getFirst().proposalId(), true);
        assertEquals(2, rides.driverRides(driver.token, client).size(), "two clients in the same car");
        assertFalse(offered.apply(com.waka.mobility.quote.PersistentQuoteService.Mode.SHARED), "120 + 120 + 120 s would pass five minutes");
        assertEquals(240, (int) jdbc.queryForObject("SELECT detour_used_seconds FROM driver_presence WHERE gate_hash=? AND account_id=?",
                Integer.class, gate, driver.account.toString()));

        // Both rides end: the counter starts again from zero for the next client.
        rides.cancelByClient(rider.token, client, r1.reservationId());
        rides.cancelByClient(rider.token, client, r2.reservationId());
        assertTrue(rides.driverRides(driver.token, client).isEmpty());
        assertTrue(offered.apply(com.waka.mobility.quote.PersistentQuoteService.Mode.SHARED));
        leg.set(200);
        rides.respond(driver.token, client, rides.proposals(driver.token, client).getFirst().proposalId(), false);
        assertFalse(offered.apply(com.waka.mobility.quote.PersistentQuoteService.Mode.SHARED), "a 400 s detour alone is refused");

        // Without a direction the car is as in R13: one reservation, VIP possible.
        leg.set(60);
        rides.presence(driver.token, client, true, a);
        assertTrue(offered.apply(com.waka.mobility.quote.PersistentQuoteService.Mode.VIP));
        assertEquals(0, rides.proposals(driver.token, client).getFirst().detourSeconds());
    }

    @Test void privateDriverTrialStartsWithTheFirstRideThenAPaidPeriodFollowsIt() {
        Login driver = login(candidatePhone);
        UUID app = readyFile(current, driver, "private", "holder", "PR 22-CD", false);
        clock.advance(61);
        Login agent = login(agentPhone);
        char[] adminToken = adminSession(agent);
        var detail = current.reviews.detail(agent.token, client, adminToken, app);
        current.reviews.decide(agent.token, client, adminToken, app, UUID.randomUUID(), detail.application().version(), "approve",
                "ALL_CONDITIONS_MET", null, allPhotos(detail, "acceptable"), allDocuments(detail, "acceptable"),
                List.of(new AdmissionReviewService.Waiver("REGISTRATION_CERTIFICATE", "LATER"), new AdmissionReviewService.Waiver("INSURANCE", "LATER")));
        clock.advance(61);
        Login rider = login(thirdPhone);
        SecureRandom random = new SecureRandom();
        byte[][] keys = new byte[6][32];
        for (byte[] k : keys) random.nextBytes(k);
        var fares = new com.waka.pricing.infrastructure.JdbcFareScheduleRepository(jdbc);
        var quotes = new com.waka.mobility.quote.PersistentQuoteService(sessions, jdbc, new com.waka.identity.profile.ProfileCrypto(keys[0], keys[1], "test-key", random),
                fares, "launch-v1", (a, b) -> new java.math.BigDecimal("3000"), com.waka.geo.CircleServiceArea.LAUNCH, clock);
        var rides = new com.waka.mobility.ride.PersistentRideService(sessions, jdbc, current.applications, quotes, fares, (from, to) -> 300,
                new com.waka.identity.profile.ProfileCrypto(keys[2], keys[3], "test-key", random), clock);
        var subscriptions = new com.waka.drivers.SubscriptionService(sessions, jdbc, manager, current.applications,
                new com.waka.identity.profile.ProfileCrypto(keys[4], keys[5], "test-key", random), clock);
        var access = new com.waka.drivers.DriverAccessService(sessions, current.applications);
        var centre = new com.waka.geo.GeoPoint(new java.math.BigDecimal("3.848"), new java.math.BigDecimal("11.502"));
        var there = new com.waka.geo.GeoPoint(new java.math.BigDecimal("3.87"), new java.math.BigDecimal("11.52"));

        assertEquals("provisional", access.access(driver.token, client).access());
        assertEquals(com.waka.drivers.SubscriptionException.Code.TRIAL_NOT_STARTED, assertThrows(com.waka.drivers.SubscriptionException.class,
                () -> subscriptions.purchase(driver.token, client, UUID.randomUUID())).code(), "R12: no purchase before the first service");
        rides.presence(driver.token, client, true, centre);
        var quote = quotes.create(rider.token, client, UUID.randomUUID(), com.waka.mobility.quote.PersistentQuoteService.Mode.SHARED, 1, centre, there);
        rides.search(rider.token, client, UUID.randomUUID(), quote.quoteId());
        var ride = rides.respond(driver.token, client, rides.proposals(driver.token, client).getFirst().proposalId(), true);
        jdbc.update("UPDATE ride_reservation SET state='confirmed' WHERE gate_hash=? AND id=?", gate, ride.reservationId().toString());
        String code = rides.ride(rider.token, client, ride.reservationId()).boardingCode();
        rides.driverAction(driver.token, client, ride.reservationId(), "board", code);
        rides.driverAction(driver.token, client, ride.reservationId(), "finish", null);
        var trial = access.access(driver.token, client);
        assertEquals("active", trial.access());
        assertEquals("trial", trial.basis());
        assertEquals("running", trial.trial());

        UUID key = UUID.randomUUID();
        var bought = subscriptions.purchase(driver.token, client, key);
        assertEquals("awaiting_payment", bought.state());
        assertEquals(3000, bought.priceXaf());
        assertEquals(bought.periodId(), subscriptions.purchase(driver.token, client, key).periodId(), "same key, same purchase");
        var paid = subscriptions.markPaid(bought.periodId());
        var trialEnd = jdbc.queryForObject("SELECT ends_at FROM driver_trial WHERE gate_hash=?", java.time.LocalDateTime.class, gate)
                .toInstant(java.time.ZoneOffset.UTC);
        assertEquals(trialEnd, paid.startsAt(), "a period bought during the trial starts when the trial ends");
        assertEquals(trialEnd.plus(Duration.ofDays(30)), paid.endsAt());
        assertEquals(paid.startsAt(), subscriptions.markPaid(bought.periodId()).startsAt(), "repeat changes nothing");

        clock.advance(Duration.ofDays(8).toSeconds());
        Login later = login(candidatePhone);
        var periodAccess = access.access(later.token, client);
        assertEquals("paid_period", periodAccess.basis());
        assertEquals("ended", periodAccess.trial());
        clock.advance(Duration.ofDays(30).toSeconds());
        Login muchLater = login(candidatePhone);
        assertEquals("subscription_required", access.access(muchLater.token, client).access());
        assertEquals(com.waka.mobility.ride.RideException.Code.DRIVER_NOT_ALLOWED, assertThrows(com.waka.mobility.ride.RideException.class,
                () -> rides.presence(muchLater.token, client, true, centre)).code(), "no new rides without a subscription");
    }

    @Test void paymentsHoldTheMoneyUntilTheServiceAndEveryFrancIsAccountedFor() {
        Login driver = login(candidatePhone);
        UUID app = readyFile(current, driver, "taxi", "holder", "PY 33-AB", false);
        clock.advance(61);
        Login agent = login(agentPhone);
        admin.bootstrapFounder(agent.account, Set.of(AdminFunction.ADMISSION, AdminFunction.FINANCE));
        char[] adminToken = openSession(agent);
        var detail = current.reviews.detail(agent.token, client, adminToken, app);
        var waivers = new ArrayList<AdmissionReviewService.Waiver>();
        for (String code : List.of("REGISTRATION_CERTIFICATE", "INSURANCE", "TECHNICAL_INSPECTION", "TRANSPORT_LICENCE", "PUBLIC_TRANSPORT_CARD")) {
            waivers.add(new AdmissionReviewService.Waiver(code, "TO_PROVIDE_AFTER_START"));
        }
        current.reviews.decide(agent.token, client, adminToken, app, UUID.randomUUID(), detail.application().version(), "approve",
                "ALL_CONDITIONS_MET", null, allPhotos(detail, "acceptable"), allDocuments(detail, "acceptable"), waivers);
        clock.advance(61);
        Login rider = login(thirdPhone);
        SecureRandom random = new SecureRandom();
        byte[][] k = new byte[6][32];
        for (byte[] key : k) random.nextBytes(key);
        var fares = new com.waka.pricing.infrastructure.JdbcFareScheduleRepository(jdbc);
        var quotes = new com.waka.mobility.quote.PersistentQuoteService(sessions, jdbc, new com.waka.identity.profile.ProfileCrypto(k[0], k[1], "test-key", random),
                fares, "launch-v1", (a, b) -> new java.math.BigDecimal("9000"), com.waka.geo.CircleServiceArea.LAUNCH, clock);
        var rides = new com.waka.mobility.ride.PersistentRideService(sessions, jdbc, current.applications, quotes, fares, (from, to) -> 300,
                new com.waka.identity.profile.ProfileCrypto(k[2], k[3], "test-key", random), clock);
        var fake = new FakeProvider();
        var pay = new com.waka.finance.PaymentService(sessions, jdbc, manager, fake, new com.waka.identity.profile.ProfileCrypto(k[4], k[5], "test-key", random), clock);
        var centre = new com.waka.geo.GeoPoint(new java.math.BigDecimal("3.848"), new java.math.BigDecimal("11.502"));
        var there = new com.waka.geo.GeoPoint(new java.math.BigDecimal("3.87"), new java.math.BigDecimal("11.52"));
        java.util.function.Function<Runnable, com.waka.finance.PaymentException.Code> refused =
                action -> assertThrows(com.waka.finance.PaymentException.class, action::run).code();
        java.util.function.Function<Login, com.waka.mobility.ride.PersistentRideService.RideView> newRide = who -> {
            rides.presence(driver.token, client, true, centre);
            var quote = quotes.create(who.token, client, UUID.randomUUID(), com.waka.mobility.quote.PersistentQuoteService.Mode.SHARED, 1, centre, there);
            rides.search(who.token, client, UUID.randomUUID(), quote.quoteId());
            return rides.respond(driver.token, client, rides.proposals(driver.token, client).getFirst().proposalId(), true);
        };

        // Top-up: WAKA bears the 3 % collection fee, so the client is charged and credited the same amount;
        // nothing is credited before the provider says so.
        UUID topupKey = UUID.randomUUID();
        var topup = pay.pay(rider.token, client, topupKey, "topup", null, "cm.mtn", "+237670000000", 5000);
        assertEquals(5000, topup.chargedXaf());
        assertEquals(0, topup.feeXaf());
        assertEquals("pending", topup.state());
        assertEquals(5000L, fake.collected.get(topup.paymentId().toString()));
        assertEquals(topup.paymentId(), pay.pay(rider.token, client, topupKey, "topup", null, "cm.mtn", "+237670000000", 5000).paymentId(), "same key");
        assertEquals(0, pay.wallet(rider.token, client).creditXaf());
        fake.settle(topup.paymentId(), com.waka.finance.PaymentProvider.State.SUCCEEDED, 5000L);
        assertEquals("succeeded", pay.read(rider.token, client, topup.paymentId()).state());
        assertEquals(5000, pay.wallet(rider.token, client).creditXaf());
        // The 300 000 F cap counts the credit and the deposits still waiting: 5 000 + 200 000 passes, 100 000 more does not.
        assertEquals("pending", pay.pay(rider.token, client, UUID.randomUUID(), "topup", null, "cm.mtn", "+237670000000", 200_000).state());
        assertEquals(com.waka.finance.PaymentException.Code.CREDIT_LIMIT,
                refused.apply(() -> pay.pay(rider.token, client, UUID.randomUUID(), "topup", null, "cm.mtn", "+237670000000", 100_000)));

        // A ride paid with credit: the money waits in the ride's hold, then goes to the driver and WAKA at the end.
        var first = newRide.apply(rider);
        int price = (int) first.priceXaf();
        var paid = pay.pay(rider.token, client, UUID.randomUUID(), "ride", first.reservationId(), "credit", null, null);
        assertTrue(paid.applied());
        assertEquals("confirmed", rides.ride(rider.token, client, first.reservationId()).state());
        assertEquals(5000 - price, pay.wallet(rider.token, client).creditXaf());
        assertEquals(com.waka.finance.PaymentException.Code.NOT_PAYABLE,
                refused.apply(() -> pay.pay(rider.token, client, UUID.randomUUID(), "ride", first.reservationId(), "credit", null, null)), "paid once");
        String hold = com.waka.finance.Ledger.hold("ride", first.reservationId().toString());
        assertEquals(price, com.waka.finance.Ledger.balance(jdbc, gate, hold));
        rides.driverAction(driver.token, client, first.reservationId(), "board", rides.ride(rider.token, client, first.reservationId()).boardingCode());
        rides.driverAction(driver.token, client, first.reservationId(), "finish", null);
        int driverShare = (int) first.driverXaf();
        assertEquals(0, com.waka.finance.Ledger.balance(jdbc, gate, hold));
        assertEquals(driverShare, pay.wallet(driver.token, client).earningsXaf(), "R03 share in the driver's earnings");
        assertEquals(0, pay.wallet(driver.token, client).creditXaf());

        // A ride paid by Orange Money through a signed notification, then cancelled: R08 full refund to the credit.
        var second = newRide.apply(rider);
        var momo = pay.pay(rider.token, client, UUID.randomUUID(), "ride", second.reservationId(), "cm.orange", "+237690000000", null);
        assertEquals(price, momo.chargedXaf(), "no fee on a direct payment");
        fake.settle(momo.paymentId(), com.waka.finance.PaymentProvider.State.SUCCEEDED, (long) price);
        byte[] note = ("{\"type\":\"payment.complete\",\"data\":{\"reference\":\"fk-" + momo.paymentId() + "\"}}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(com.waka.finance.PaymentException.Code.NOT_AUTHENTIC, refused.apply(() -> pay.notification(note, name -> "forged")));
        assertEquals("awaiting_payment", rides.ride(rider.token, client, second.reservationId()).state());
        pay.notification(note, name -> "good");
        assertEquals("confirmed", rides.ride(rider.token, client, second.reservationId()).state());
        rides.cancelByClient(rider.token, client, second.reservationId());
        assertEquals(5000, pay.wallet(rider.token, client).creditXaf(), "paid by Orange Money, refunded in full to the credit");

        // Paid after the three-minute window: the service is not confirmed and the money stays as credit (R07).
        var third = newRide.apply(rider);
        var late = pay.pay(rider.token, client, UUID.randomUUID(), "ride", third.reservationId(), "cm.mtn", "+237670000000", null);
        clock.advance(181);
        fake.settle(late.paymentId(), com.waka.finance.PaymentProvider.State.SUCCEEDED, (long) price);
        var lateView = pay.read(rider.token, client, late.paymentId());
        assertEquals("succeeded", lateView.state());
        assertFalse(lateView.applied());
        assertEquals(5000 + price, pay.wallet(rider.token, client).creditXaf());

        // The provider's amount must match exactly; otherwise nothing is credited and finance reviews it.
        var odd = pay.pay(rider.token, client, UUID.randomUUID(), "topup", null, "cm.mtn", "+237670000000", 1000);
        fake.settle(odd.paymentId(), com.waka.finance.PaymentProvider.State.SUCCEEDED, 999L);
        assertEquals("review", pay.read(rider.token, client, odd.paymentId()).state());
        assertEquals(5000 + price, pay.wallet(rider.token, client).creditXaf());

        // Withdrawals go only to the account's own number, 3 % on the credit; a failed transfer gives everything back.
        assertEquals(com.waka.finance.PaymentException.Code.PHONE_NOT_ACCOUNT,
                refused.apply(() -> pay.withdraw(rider.token, client, UUID.randomUUID(), "credit", 500, "cm.mtn", candidatePhone)));
        var failed = pay.withdraw(rider.token, client, UUID.randomUUID(), "credit", 500, "cm.mtn", thirdPhone);
        assertEquals(15, failed.feeXaf());
        assertEquals("pending", failed.state());
        assertEquals(5000 + price - 515, pay.wallet(rider.token, client).creditXaf());
        fake.settle(failed.withdrawalId(), com.waka.finance.PaymentProvider.State.FAILED, null);
        assertEquals("failed", pay.readWithdrawal(rider.token, client, failed.withdrawalId()).state());
        assertEquals(5000 + price, pay.wallet(rider.token, client).creditXaf());
        var sent = pay.withdraw(rider.token, client, UUID.randomUUID(), "credit", 500, "cm.mtn", thirdPhone);
        fake.settle(sent.withdrawalId(), com.waka.finance.PaymentProvider.State.SUCCEEDED, 500L);
        assertEquals("succeeded", pay.readWithdrawal(rider.token, client, sent.withdrawalId()).state());
        assertEquals(5000 + price - 515, pay.wallet(rider.token, client).creditXaf());
        assertEquals(com.waka.finance.PaymentException.Code.INSUFFICIENT_CREDIT,
                refused.apply(() -> pay.withdraw(rider.token, client, UUID.randomUUID(), "credit", 200_000, "cm.mtn", thirdPhone)));

        // A driver withdraws earnings at 2 %.
        var earned = pay.withdraw(driver.token, client, UUID.randomUUID(), "earnings", 500, "cm.mtn", candidatePhone);
        assertEquals(10, earned.feeXaf());
        fake.settle(earned.withdrawalId(), com.waka.finance.PaymentProvider.State.SUCCEEDED, 500L);
        assertEquals("succeeded", pay.readWithdrawal(driver.token, client, earned.withdrawalId()).state());
        assertEquals(driverShare - 510, pay.wallet(driver.token, client).earningsXaf());

        // Every franc: what the provider holds for WAKA equals what was collected minus what was sent out,
        // and it is exactly the people's credit plus WAKA's revenue, with no money left in any hold.
        long atProvider = -com.waka.finance.Ledger.balance(jdbc, gate, "provider:fake");
        assertEquals(5000 + price + price - 500 - 500, atProvider);
        long revenue = com.waka.finance.Ledger.balance(jdbc, gate, com.waka.finance.Ledger.REVENUE);
        assertEquals((price - driverShare) + 15 + 10, revenue, "R03 commission, then 3 % and 2 % withdrawal fees");
        assertEquals(atProvider, (5000 + price - 515) + (driverShare - 510) + revenue);
        assertEquals(0L, jdbc.queryForObject("SELECT COALESCE(SUM(CASE WHEN credit_account LIKE 'hold:%' THEN amount_xaf "
                + "WHEN debit_account LIKE 'hold:%' THEN -amount_xaf ELSE 0 END),0) FROM ledger_entry WHERE gate_hash=?", Long.class, gate),
                "no money left in a hold");

        // FINANCE: the totals match the money at the provider; a mismatched payment is settled by a person, once.
        var finance = new com.waka.finance.FinanceAdminService(sessions, admin, jdbc);
        var overview = finance.overview(agent.token, client, adminToken);
        assertEquals(atProvider, overview.expectedAtProviderXaf());
        assertEquals(revenue, overview.revenueXaf());
        assertEquals(5000 + price - 515, overview.creditXaf());
        assertEquals(driverShare - 510, overview.earningsXaf());
        assertEquals(0, overview.heldXaf());
        assertEquals(overview.expectedAtProviderXaf(), overview.creditXaf() + overview.earningsXaf() + overview.heldXaf()
                + overview.payoutsInFlightXaf() + overview.revenueXaf(), "every franc at the provider belongs to someone");
        var review = finance.cases(agent.token, client, adminToken).stream().filter(c -> c.id().equals(odd.paymentId())).findFirst().orElseThrow();
        assertEquals("review", review.state());
        assertEquals(AdminException.Code.INVALID_REQUEST, assertThrows(AdminException.class, () -> finance.resolvePayment(agent.token, client,
                adminToken, odd.paymentId(), "received", 1001, "PROVIDER_DASHBOARD_CHECKED")).code(), "never more than asked");
        assertEquals("succeeded", finance.resolvePayment(agent.token, client, adminToken, odd.paymentId(), "received", 999,
                "PROVIDER_DASHBOARD_CHECKED").state());
        assertEquals(5000 + price - 515 + 999, pay.wallet(rider.token, client).creditXaf(), "what really arrived goes to the credit");
        assertEquals(AdminException.Code.NOT_FOUND, assertThrows(AdminException.class, () -> finance.resolvePayment(agent.token, client,
                adminToken, odd.paymentId(), "not_received", 0, "PROVIDER_DASHBOARD_CHECKED")).code(), "settled once");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE event_type='PAYMENT_RESOLVED' AND object_ref=?",
                Integer.class, odd.paymentId().toString()));
        var after = finance.overview(agent.token, client, adminToken);
        assertEquals(atProvider + 999, after.expectedAtProviderXaf());
    }

    private static final class FakeProvider implements com.waka.finance.PaymentProvider {
        final Map<String, Long> collected = new java.util.concurrent.ConcurrentHashMap<>();
        final Map<String, Status> statuses = new java.util.concurrent.ConcurrentHashMap<>();
        @Override public String name() { return "fake"; }
        @Override public Started collect(String reference, long amountXaf, String channel, String phone, String description) {
            collected.put(reference, amountXaf);
            return new Started("fk-" + reference);
        }
        @Override public Status payment(String reference) { return statuses.getOrDefault(reference, new Status(State.PENDING, null, null)); }
        @Override public Started send(String reference, long amountXaf, String channel, String phone, String description) {
            return new Started("fk-" + reference);
        }
        @Override public Status transfer(String reference) { return payment(reference); }
        @Override public boolean authentic(byte[] body, java.util.function.UnaryOperator<String> header) { return "good".equals(header.apply("x-wh-secret")); }
        @Override public Set<String> references(byte[] body) {
            var m = java.util.regex.Pattern.compile("fk-[0-9a-f-]{36}").matcher(new String(body, java.nio.charset.StandardCharsets.UTF_8));
            return m.find() ? Set.of(m.group()) : Set.of();
        }
        void settle(UUID id, State state, Long amount) { statuses.put("fk-" + id, new Status(state, amount, "XAF")); }
    }

    @Test void renewalReusesValidPiecesAndVehiclePiecesFollowTheRegistration() {
        Login candidate = login(candidatePhone);
        UUID first = readyFile(established, candidate, "taxi", "holder", "AB 12-CD", true);
        clock.advance(61);
        Login agent = login(agentPhone);
        char[] adminToken = adminSession(agent);
        var detail = established.reviews.detail(agent.token, client, adminToken, first);
        established.reviews.decide(agent.token, client, adminToken, first, UUID.randomUUID(), detail.application().version(),
                "refuse", "VEHICLE_NOT_ELIGIBLE", null, List.of(), allDocuments(detail, "acceptable"));

        UUID second = established.applications.create(candidate.token, client, UUID.randomUUID(), "taxi").applicationId();
        established.applications.updateVehicle(candidate.token, client, second, UUID.randomUUID(), 1, car("holder", "AB 12-CD"));
        var sameVehicle = established.applications.requirements(candidate.token, client, second);
        assertTrue(sameVehicle.stream().allMatch(RequirementStatus::satisfied), "every valid accepted piece is reused, nothing uploaded again");

        established.applications.updateVehicle(candidate.token, client, second, UUID.randomUUID(), 2, car("holder", "CD 34-EF"));
        var otherVehicle = established.applications.requirements(candidate.token, client, second);
        Map<String, Boolean> satisfied = otherVehicle.stream().collect(Collectors.toMap(RequirementStatus::code, RequirementStatus::satisfied));
        assertTrue(satisfied.get("IDENTITY") && satisfied.get("DRIVING_LICENCE"), "person pieces follow the person");
        assertFalse(satisfied.get("REGISTRATION_CERTIFICATE") || satisfied.get("INSURANCE"), "vehicle pieces follow the registration");

        clock.advance(Duration.ofDays(400).toSeconds());
        Login later = login(candidatePhone);
        var expired = established.applications.requirements(later.token, client, second);
        assertTrue(statusOf(expired, "IDENTITY").expired(), "an expired piece must be provided again");
        assertFalse(statusOf(expired, "IDENTITY").satisfied());
    }

    @Test void agentScopeConflictsAndFunctionAreEnforced() {
        Login agent = login(agentPhone);
        UUID own = readyFile(v1, agent, "taxi", "holder", "AB 12-CD", false);
        char[] adminToken = adminSession(agent);
        assertTrue(v1.reviews.workItems(agent.token, client, adminToken).isEmpty(), "own file never listed");
        assertAdmission(AdmissionException.Code.CONFLICT_OF_INTEREST, () -> v1.reviews.detail(agent.token, client, adminToken, own));
        UUID ownDocument = UUID.fromString(jdbc.queryForObject("SELECT id FROM driver_document WHERE gate_hash=? LIMIT 1", String.class, gate));
        assertFile(FileException.Code.NOT_FOUND, () -> v1.documents.examinationContent(agent.token, client, adminToken, ownDocument));

        clock.advance(61);
        Login candidate = login(candidatePhone);
        UUID draft = v1.applications.create(candidate.token, client, UUID.randomUUID(), "taxi").applicationId();
        UUID draftDocument = upload(v1, candidate, draft, "IDENTITY").documentId();
        assertFile(FileException.Code.NOT_FOUND, () -> v1.documents.examinationContent(agent.token, client, adminToken, draftDocument),
                "pieces of a candidate who never submitted stay private");
        assertFile(FileException.Code.NOT_FOUND, () -> v1.documents.content(candidate.token, client, ownDocument));
        assertTrue(v1.documents.content(candidate.token, client, draftDocument).jpeg().length > 0);

        clock.advance(61);
        Login auditor = login(thirdPhone);
        jdbc.update("INSERT INTO admin_grant (gate_hash,id,account_id,admin_function,granted_at,granted_by,reason_code) VALUES (?,?,?,?,NOW(6),'TEST','TEST')",
                gate, UUID.randomUUID().toString(), auditor.account.toString(), "AUDIT");
        char[] auditorToken = openSession(auditor);
        assertAdmission(AdmissionException.Code.NOT_AUTHORIZED, () -> v1.reviews.workItems(auditor.token, client, auditorToken));
        assertFile(FileException.Code.NOT_AUTHORIZED, () -> v1.documents.examinationContent(auditor.token, client, auditorToken, ownDocument));
    }

    /** A complete, submitted file; {@code allPieces} also uploads the optional requirements. */
    private UUID readyFile(Stack stack, Login owner, String category, String right, String plate, boolean allPieces) {
        UUID app = stack.applications.create(owner.token, client, UUID.randomUUID(), category).applicationId();
        stack.applications.updateVehicle(owner.token, client, app, UUID.randomUUID(), 1, car(right, plate));
        for (String view : AdmissionReviewService.VIEWS) photos.upload(owner.token, client, app, view, UUID.randomUUID(), "image/jpeg", jpeg);
        for (var requirement : stack.applications.requirements(owner.token, client, app)) {
            if (allPieces || requirement.requiredForSubmission()) upload(stack, owner, app, requirement.code());
        }
        stack.applications.submit(owner.token, client, app, UUID.randomUUID(), 2);
        return app;
    }
    private DriverDocumentService.DocumentView upload(Stack stack, Login owner, UUID app, String code) {
        return stack.documents.upload(owner.token, client, app, code, UUID.randomUUID(), "image/jpeg", jpeg, nextYear);
    }
    private static VehicleFields car(String right, String plate) {
        return new VehicleFields("Marque", "Modele", 2020, "Bleu", plate, 5, 4, right);
    }
    private static List<String> codes(List<RequirementStatus> statuses) { return statuses.stream().map(RequirementStatus::code).toList(); }
    private static RequirementStatus statusOf(List<RequirementStatus> statuses, String code) {
        return statuses.stream().filter(s -> s.code().equals(code)).findFirst().orElseThrow();
    }
    private static UUID documentOf(List<RequirementStatus> statuses, String code) { return statusOf(statuses, code).documentId(); }
    private static List<AdmissionReviewService.PhotoReview> allPhotos(AdmissionReviewService.Detail detail, String result) {
        return detail.photos().stream().map(p -> new AdmissionReviewService.PhotoReview(p.view(), p.fileId(), result, null)).toList();
    }
    private static List<AdmissionReviewService.DocumentReview> allDocuments(AdmissionReviewService.Detail detail, String result) {
        return detail.requirements().stream().filter(r -> r.documentId() != null)
                .map(r -> new AdmissionReviewService.DocumentReview(r.documentId(), null, result, null)).toList();
    }
    private char[] adminSession(Login agent) {
        admin.bootstrapFounder(agent.account, Set.of(AdminFunction.ADMISSION));
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
    private static void assertAdmission(AdmissionException.Code code, Runnable operation) { assertAdmission(code, operation, ""); }
    private static void assertAdmission(AdmissionException.Code code, Runnable operation, String reason) {
        assertEquals(code, assertThrows(AdmissionException.class, operation::run, reason).code(), reason);
    }
    private static void assertFile(FileException.Code code, Runnable operation) { assertFile(code, operation, ""); }
    private static void assertFile(FileException.Code code, Runnable operation, String reason) {
        assertEquals(code, assertThrows(FileException.class, operation::run, reason).code(), reason);
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
