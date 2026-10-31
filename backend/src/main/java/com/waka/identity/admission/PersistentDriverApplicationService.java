package com.waka.identity.admission;

import com.waka.identity.session.PersistentSessionService;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A26-A28 core: the owner's draft candidature and declared vehicle. Never an admission:
 * files, submission, decisions and access belong to later points. Construct explicitly
 * after qualifying keys and maintenance policy; the ordinary application does not assemble it.
 */
public final class PersistentDriverApplicationService {
    public record ApplicationView(UUID applicationId, UUID accountId, String requestedCategory, String state,
            long version, VehicleFields vehicleFields, List<String> missingVehicleFields, Instant createdAt,
            Instant updatedAt, Instant serverTime, boolean idempotentReplay) {
        @Override public String toString() { return "ApplicationView[REDACTED]"; }
    }
    private record Row(UUID id, String category, String state, long version, String keyId, byte[] cipher,
            Instant createdAt, Instant updatedAt) {
        @Override public String toString() { return "ApplicationRow[REDACTED]"; }
    }
    private record CommandRow(String payloadMac, String keyId, byte[] cipher, Instant createdAt, Instant expiresAt) {
        @Override public String toString() { return "ApplicationCommandRow[REDACTED]"; }
    }
    private record Outcome(ApplicationView view, AdmissionException.Code refusal) { }
    private static final String DRAFT = "draft";
    static final Set<String> STATES = Set.of("draft", "submitted", "correction_requested", "refused", "approved");
    /** States in which the candidate may change the vehicle or photos. */
    public static final Set<String> EDITABLE = Set.of("draft", "correction_requested");
    private static final long MAX_VERSION = 9007199254740991L;
    /** Validity dates are read in Cameroon's civil time. */
    static final ZoneId CAMEROON = ZoneId.of("Africa/Douala");
    /** Vehicle key of PERSON documents, which do not depend on a vehicle. */
    public static final String PERSON_KEY = "-";
    /** Opaque context used by the document service inside its own authenticated transaction. */
    public record RequirementContext(String state, String category, String usageRight, String vehicleKey) {
        @Override public String toString() { return "RequirementContext[REDACTED]"; }
    }
    /**
     * One applicable requirement. {@code requiredForSubmission} is true for ESTABLISHED person documents
     * (identity, licence), which are never waived; vehicle documents may be missing at submission and are
     * judged, or waived with a reason, by the agent (catalogue v2). A TO_CONFIRM
     * requirement is never {@code satisfied}.
     */
    public record RequirementStatus(String code, String document, String explanation, String status,
            boolean requiredForSubmission, UUID documentId, String reviewState, LocalDate validUntil,
            boolean expired, boolean satisfied) { }
    private final PersistentSessionService sessions;
    private final JdbcTemplate jdbc;
    private final AdmissionCrypto crypto;
    private final Duration responseLifetime;
    private final String gate;
    private final TransactionTemplate maintenance;
    private final Clock clock;

    private final List<DocumentCatalog.Requirement> catalog;

    /** Uses the catalogue in force, {@link DocumentCatalog#V1}. */
    public PersistentDriverApplicationService(PersistentSessionService sessions, JdbcTemplate jdbc,
            DataSourceTransactionManager manager, AdmissionCrypto crypto, Duration responseLifetime, Clock clock) {
        this(sessions, jdbc, manager, crypto, responseLifetime, clock, DocumentCatalog.CURRENT);
    }

    /** A different catalogue is a new, dated version decided by the founder (or a test fixture). */
    public PersistentDriverApplicationService(PersistentSessionService sessions, JdbcTemplate jdbc,
            DataSourceTransactionManager manager, AdmissionCrypto crypto, Duration responseLifetime, Clock clock,
            List<DocumentCatalog.Requirement> catalog) {
        this.catalog = List.copyOf(catalog);
        this.sessions = Objects.requireNonNull(sessions);
        this.jdbc = Objects.requireNonNull(jdbc);
        this.crypto = Objects.requireNonNull(crypto);
        this.clock = Objects.requireNonNull(clock);
        if (jdbc.getDataSource() == null || jdbc.getDataSource() != Objects.requireNonNull(manager).getDataSource()
                || !sessions.usesDataSource(jdbc.getDataSource()) || !sessions.usesClock(clock))
            throw new IllegalArgumentException("ADMISSION_DEPENDENCIES_MISMATCH");
        if (crypto.reusesSessionKey(sessions)) throw new IllegalArgumentException("ADMISSION_SESSION_KEY_REUSE");
        if (responseLifetime == null || responseLifetime.isNegative() || responseLifetime.isZero()
                || responseLifetime.getNano() % 1000 != 0 || responseLifetime.compareTo(Duration.ofDays(365)) > 0) {
            throw new IllegalArgumentException("ADMISSION_RESPONSE_LIFETIME_REQUIRED");
        }
        this.responseLifetime = responseLifetime;
        this.gate = sessions.identityGateHash();
        maintenance = new TransactionTemplate(manager);
        maintenance.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        maintenance.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * A26: a declared category creates a draft only; one open candidature per account. R16: an approved driver may ask
     * for the other category (taxi <-> private): his approved file keeps serving while the new one is examined.
     */
    public ApplicationView create(char[] token, String client, UUID key, String requestedCategory) {
        if (key == null || !("taxi".equals(requestedCategory) || "private".equals(requestedCategory))) throw invalid();
        return unwrap(sessions.withAuthenticatedSession(token, client, session -> {
            UUID account = session.accountId();
            String actor = account.toString();
            String request = crypto.mac("admission-request-v1", actor, "POST /api/v1/driver-applications", key.toString());
            String payload = crypto.mac("admission-create-v1", actor, requestedCategory);
            Instant now = session.serverTime();
            Outcome replay = replay(account, request, payload, now);
            if (replay != null) return replay;
            Row open = openApplication(account);
            String replaces = null;
            if (open != null) {
                if (!"approved".equals(open.state) || open.category.equals(requestedCategory) || servingApplication(account) != null) {
                    return new Outcome(null, AdmissionException.Code.APPLICATION_ALREADY_OPEN);
                }
                // The approved profile keeps serving until the new file is decided.
                jdbc.update("UPDATE driver_application SET open_marker=NULL,serving_marker=1 WHERE gate_hash=? AND id=?", gate, open.id.toString());
                replaces = open.id.toString();
            }
            UUID id = UUID.randomUUID();
            byte[] vehicle = encryptVehicle(actor, id, 1, now, VehicleFields.EMPTY);
            try {
                jdbc.update("INSERT INTO driver_application (gate_hash,id,account_id,requested_category,state,open_marker,version,key_id,vehicle_cipher,created_at,updated_at,replaces_id) VALUES (?,?,?,?,?,1,1,?,?,?,?,?)",
                        gate, id.toString(), actor, requestedCategory, DRAFT, crypto.keyId(), vehicle, sql(now), sql(now), replaces);
            } finally { Arrays.fill(vehicle, (byte) 0); }
            ApplicationView view = view(id, account, requestedCategory, DRAFT, 1, VehicleFields.EMPTY, now, now, now, false);
            remember(actor, request, payload, view, now);
            return new Outcome(view, null);
        }));
    }

    /** Finds the caller's own open candidature without knowing its identifier. */
    public ApplicationView readCurrent(char[] token, String client) {
        return unwrap(sessions.withAuthenticatedSession(token, client, session -> {
            Row row = openApplication(session.accountId());
            if (row == null) return new Outcome(null, AdmissionException.Code.NOT_FOUND);
            return new Outcome(current(session.accountId(), row, session.serverTime()), null);
        }));
    }

    /** A27: owner only; another candidate's identifier is indistinguishable from an unknown one. */
    public ApplicationView read(char[] token, String client, UUID applicationId) {
        if (applicationId == null) throw invalid();
        return unwrap(sessions.withAuthenticatedSession(token, client, session -> {
            Row row = owned(session.accountId(), applicationId);
            if (row == null) return new Outcome(null, AdmissionException.Code.NOT_FOUND);
            return new Outcome(current(session.accountId(), row, session.serverTime()), null);
        }));
    }

    /** A28 limited to the eight declared vehicle fields; the whole object replaces the previous one. */
    public ApplicationView updateVehicle(char[] token, String client, UUID applicationId, UUID key,
            long expectedVersion, VehicleFields input) {
        if (applicationId == null || key == null || input == null || expectedVersion < 1 || expectedVersion > MAX_VERSION) {
            throw invalid();
        }
        // Validated before the session transaction, whose wrapper reports any inner error as unavailable.
        VehicleFields fields = VehicleFields.declared(input.brand(), input.model(), input.year(), input.color(),
                input.registration(), input.authorizedTotalCapacity(), input.offeredPassengerSeats(),
                input.usageRight(), clock.instant().atZone(ZoneOffset.UTC).getYear());
        return unwrap(sessions.withAuthenticatedSession(token, client, session -> {
            UUID account = session.accountId();
            String actor = account.toString();
            Instant now = session.serverTime();
            String request = crypto.mac("admission-request-v1", actor,
                    "PATCH /api/v1/driver-applications/" + applicationId, key.toString());
            byte[] encoded = encodeVehicle(fields);
            String payload;
            try { payload = crypto.mac("admission-vehicle-v1", actor, applicationId.toString(),
                    Long.toString(expectedVersion), HexFormat.of().formatHex(encoded)); }
            finally { Arrays.fill(encoded, (byte) 0); }
            Outcome replay = replay(account, request, payload, now);
            if (replay != null) return replay;
            Row row = owned(account, applicationId);
            if (row == null) return new Outcome(null, AdmissionException.Code.NOT_FOUND);
            if (!EDITABLE.contains(row.state)) return new Outcome(null, AdmissionException.Code.NOT_EDITABLE);
            if (expectedVersion != row.version || row.version == MAX_VERSION) {
                return new Outcome(null, AdmissionException.Code.VERSION_CONFLICT);
            }
            long next = row.version + 1;
            byte[] vehicle = encryptVehicle(actor, applicationId, next, now, fields);
            try {
                int changed = jdbc.update("UPDATE driver_application SET version=?,key_id=?,vehicle_cipher=?,updated_at=? WHERE gate_hash=? AND id=? AND account_id=? AND version=?",
                        next, crypto.keyId(), vehicle, sql(now), gate, applicationId.toString(), actor, row.version);
                if (changed != 1) return new Outcome(null, AdmissionException.Code.VERSION_CONFLICT);
            } finally { Arrays.fill(vehicle, (byte) 0); }
            ApplicationView view = view(applicationId, account, row.category, row.state, next, fields, row.createdAt, now, now, false);
            remember(actor, request, payload, view, now);
            return new Outcome(view, null);
        }));
    }

    /**
     * A29: sends a complete draft (or corrected file) to examination. Requires the eight vehicle fields,
     * the six current photos and qualified document requirements; never an approval or an access.
     */
    public ApplicationView submit(char[] token, String client, UUID applicationId, UUID key, long expectedVersion) {
        if (applicationId == null || key == null || expectedVersion < 1 || expectedVersion > MAX_VERSION) throw invalid();
        return unwrap(sessions.withAuthenticatedSession(token, client, session -> {
            UUID account = session.accountId();
            String actor = account.toString();
            Instant now = session.serverTime();
            String request = crypto.mac("admission-request-v1", actor,
                    "POST /api/v1/driver-applications/" + applicationId + "/submissions", key.toString());
            String payload = crypto.mac("admission-submit-v1", actor, applicationId.toString(), Long.toString(expectedVersion));
            Outcome replay = replay(account, request, payload, now);
            if (replay != null) return replay;
            Row row = owned(account, applicationId);
            if (row == null) return new Outcome(null, AdmissionException.Code.NOT_FOUND);
            if (!EDITABLE.contains(row.state)) return new Outcome(null, AdmissionException.Code.NOT_EDITABLE);
            if (expectedVersion != row.version || row.version == MAX_VERSION) return new Outcome(null, AdmissionException.Code.VERSION_CONFLICT);
            VehicleFields fields = current(account, row, now).vehicleFields();
            Integer photos = jdbc.queryForObject("SELECT COUNT(*) FROM vehicle_photo WHERE gate_hash=? AND application_id=? AND current_marker=1",
                    Integer.class, gate, applicationId.toString());
            if (!fields.missing().isEmpty() || photos == null || photos != 6) return new Outcome(null, AdmissionException.Code.INCOMPLETE);
            // Identity and licence must be present, valid and not waiting for a correction; vehicle
            // documents may be missing here (the agent judges or waives them); to-confirm ones are never satisfied.
            // A document the agent asked to correct must be replaced first, waivable or not.
            var statuses = statuses(account, row.category, fields, now);
            boolean documentsReady = statuses.stream().filter(RequirementStatus::requiredForSubmission)
                    .allMatch(s -> s.documentId() != null && !s.expired())
                    && statuses.stream().noneMatch(s -> "to_correct".equals(s.reviewState()));
            if (!documentsReady) return new Outcome(null, AdmissionException.Code.INCOMPLETE);
            long next = reencrypt(actor, row, fields, now);
            jdbc.update("UPDATE driver_application SET state='submitted',submitted_at=? WHERE gate_hash=? AND id=?",
                    sql(now), gate, applicationId.toString());
            ApplicationView view = view(applicationId, account, row.category, "submitted", next, fields, row.createdAt, now, now, false);
            remember(actor, request, payload, view, now);
            return new Outcome(view, null);
        }));
    }

    /** The documents of the caller's own file, only those that apply to the declared situation. */
    public List<RequirementStatus> requirements(char[] token, String client, UUID applicationId) {
        if (applicationId == null) throw invalid();
        Outcome2<List<RequirementStatus>> outcome = sessions.withAuthenticatedSession(token, client, session -> {
            Row row = owned(session.accountId(), applicationId);
            if (row == null) return new Outcome2<List<RequirementStatus>>(null, AdmissionException.Code.NOT_FOUND);
            VehicleFields fields = current(session.accountId(), row, session.serverTime()).vehicleFields();
            return new Outcome2<>(statuses(session.accountId(), row.category, fields, session.serverTime()), null);
        });
        if (outcome.refusal() != null) throw new AdmissionException(outcome.refusal());
        return outcome.value();
    }

    /**
     * For the document service, inside its authenticated transaction: the owner's file state, category,
     * declared use right and opaque vehicle key (null while no registration is declared).
     */
    public RequirementContext requirementContextLocked(UUID candidate, UUID applicationId, Instant now) {
        requireTransaction();
        Row row = owned(candidate, applicationId);
        if (row == null) return null;
        VehicleFields fields = current(candidate, row, now).vehicleFields();
        return new RequirementContext(row.state, row.category, fields.usageRight(), vehicleKey(candidate, fields));
    }

    /** What A32 needs about the caller's open file; null when the account has none. */
    /** {@code waivedCodes}: vehicle documents waived by the approving decision (catalogue v2), with their traced reason. */
    /** {@code trialEndsAt}: end of the private driver's trial once started; {@code paidUntil}: end of the paid period covering now. */
    public record AccessBasis(UUID applicationId, String category, String state, List<RequirementStatus> requirements,
            Set<String> waivedCodes, Integer offeredSeats, Instant trialEndsAt, Instant paidUntil) {
        public AccessBasis(UUID applicationId, String category, String state, List<RequirementStatus> requirements,
                Set<String> waivedCodes) {
            this(applicationId, category, state, requirements, waivedCodes, null, null, null);
        }
        @Override public String toString() { return "AccessBasis[REDACTED]"; }
    }

    /** For the access service, inside its authenticated transaction: the open file and its requirement statuses. */
    public AccessBasis accessBasisLocked(UUID candidate, Instant now) {
        requireTransaction();
        Row row = profileRow(candidate);
        if (row == null) return null;
        // Only the latest approval counts: a later file version is approved again with its own waivers.
        Set<String> waived = "approved".equals(row.state) ? Set.copyOf(jdbc.query(
                "SELECT w.requirement_code FROM admission_waiver w JOIN admission_decision d ON d.gate_hash=w.gate_hash AND d.id=w.decision_id "
                        + "WHERE w.gate_hash=? AND d.id=(SELECT id FROM admission_decision WHERE gate_hash=? AND application_id=? AND action='approve' "
                        + "ORDER BY decided_at DESC,id DESC LIMIT 1)",
                (rs, n) -> rs.getString(1), gate, gate, row.id.toString())) : Set.of();
        VehicleFields fields = current(candidate, row, now).vehicleFields();
        var trial = jdbc.query("SELECT ends_at FROM driver_trial WHERE gate_hash=? AND account_id=?",
                (rs, n) -> rs.getObject(1, LocalDateTime.class).toInstant(ZoneOffset.UTC), gate, candidate.toString());
        var paid = jdbc.query("SELECT MAX(ends_at) FROM subscription_period WHERE gate_hash=? AND account_id=? AND state='paid' AND starts_at<=? AND ends_at>?",
                (rs, n) -> { var v = rs.getObject(1, LocalDateTime.class); return v == null ? null : v.toInstant(ZoneOffset.UTC); },
                gate, candidate.toString(), LocalDateTime.ofInstant(now, ZoneOffset.UTC), LocalDateTime.ofInstant(now, ZoneOffset.UTC));
        return new AccessBasis(row.id, row.category, row.state, statuses(candidate, row.category, fields, now), waived,
                fields.offeredPassengerSeats(), trial.isEmpty() ? null : trial.getFirst(), paid.isEmpty() ? null : paid.getFirst());
    }

    /** Recorded with each decision: the catalogue version used ("v1", "v2"), or "test" for any other list. */
    String catalogueVersion() {
        return catalog.equals(DocumentCatalog.V2) ? "v2" : catalog.equals(DocumentCatalog.V1) ? "v1" : "test";
    }

    /** The requirement with this code in the catalogue in force, or null. */
    public DocumentCatalog.Requirement requirement(String code) {
        return catalog.stream().filter(r -> r.code().equals(code)).findFirst().orElse(null);
    }

    /** Requirement statuses for an examined file, inside the caller's administrative transaction. */
    public List<RequirementStatus> requirementsLocked(UUID candidate, UUID applicationId, Instant now) {
        requireTransaction();
        Row row = owned(candidate, applicationId);
        if (row == null) return List.of();
        return statuses(candidate, row.category, current(candidate, row, now).vehicleFields(), now);
    }

    private List<RequirementStatus> statuses(UUID candidate, String category, VehicleFields fields, Instant now) {
        String vehicleKey = vehicleKey(candidate, fields);
        LocalDate today = LocalDate.ofInstant(now, CAMEROON);
        record Doc(UUID id, String type, String vehicleKey, String review, LocalDate validUntil) { }
        List<Doc> docs = jdbc.query("SELECT id,document_type,vehicle_key,review_state,valid_until FROM driver_document WHERE gate_hash=? AND account_id=? AND current_marker=1",
                (rs, n) -> new Doc(UUID.fromString(rs.getString(1)), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getObject(5, LocalDate.class)), gate, candidate.toString());
        List<RequirementStatus> result = new java.util.ArrayList<>();
        for (DocumentCatalog.Requirement requirement : catalog.stream().filter(r -> r.appliesTo(category, fields.usageRight())).toList()) {
            String key = requirement.scope() == DocumentCatalog.Scope.PERSON ? PERSON_KEY : vehicleKey;
            Doc doc = key == null ? null : docs.stream()
                    .filter(d -> d.type.equals(requirement.code()) && d.vehicleKey.equals(key)).findFirst().orElse(null);
            boolean established = requirement.status() == DocumentCatalog.Status.ESTABLISHED;
            boolean expired = doc != null && doc.validUntil != null && doc.validUntil.isBefore(today);
            result.add(new RequirementStatus(requirement.code(), requirement.documentFor(category), requirement.explanationFor(category),
                    established ? "established" : "to_confirm", established && !requirement.waivable(),
                    doc == null ? null : doc.id, doc == null ? null : doc.review, doc == null ? null : doc.validUntil,
                    expired, established && doc != null && "accepted".equals(doc.review) && !expired));
        }
        return List.copyOf(result);
    }
    /** Same registration, same key: vehicle documents follow the vehicle, not the file. */
    private String vehicleKey(UUID candidate, VehicleFields fields) {
        return fields.registration() == null ? null
                : crypto.mac("admission-vehicle-key-v1", candidate.toString(), fields.registration());
    }
    /** The declared vehicle of the driver's open file, inside the caller's transaction, or null without one. */
    public VehicleFields vehicleLocked(UUID candidate, Instant now) {
        requireTransaction();
        Row row = profileRow(candidate);
        return row == null ? null : current(candidate, row, now).vehicleFields();
    }

    private static void requireTransaction() {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new AdmissionException(AdmissionException.Code.SERVICE_UNAVAILABLE);
        }
    }
    private record Outcome2<T>(T value, AdmissionException.Code refusal) { }

    /** Examination support, called inside the caller's administrative transaction (gate held). */
    record Examined(UUID candidate, ApplicationView view, Instant submittedAt) {
        @Override public String toString() { return "Examined[REDACTED]"; }
    }

    Examined examinedLocked(UUID applicationId, Instant now) {
        var rows = jdbc.query("SELECT account_id,submitted_at FROM driver_application WHERE gate_hash=? AND id=? FOR UPDATE",
                (rs, n) -> new Object[] { UUID.fromString(rs.getString(1)), rs.getObject(2, LocalDateTime.class) },
                gate, applicationId.toString());
        if (rows.isEmpty()) return null;
        UUID candidate = (UUID) rows.getFirst()[0];
        LocalDateTime submitted = (LocalDateTime) rows.getFirst()[1];
        Row row = owned(candidate, applicationId);
        return new Examined(candidate, current(candidate, row, now), submitted == null ? null : instant(submitted));
    }

    /**
     * Moves a submitted file after a decision; the vehicle is re-encrypted because its protection is
     * bound to the version. Returns the new version, or -1 when the examined version is stale.
     */
    long decideLocked(UUID candidate, UUID applicationId, long examinedVersion, String toState, Instant now) {
        Row row = owned(candidate, applicationId);
        if (row == null || !"submitted".equals(row.state) || row.version != examinedVersion || row.version == MAX_VERSION) return -1;
        VehicleFields fields = current(candidate, row, now).vehicleFields();
        long next = reencrypt(candidate.toString(), row, fields, now);
        jdbc.update("UPDATE driver_application SET state=?,open_marker=? WHERE gate_hash=? AND id=?",
                toState, "refused".equals(toState) ? null : 1, gate, applicationId.toString());
        // R16: a change of situation ends here — approved, the new file replaces the serving one; refused, nothing changes.
        Row serving = servingApplication(candidate);
        if (serving != null && "approved".equals(toState)) {
            jdbc.update("UPDATE driver_application SET state='replaced',serving_marker=NULL WHERE gate_hash=? AND id=?", gate, serving.id.toString());
        } else if (serving != null && "refused".equals(toState)) {
            jdbc.update("UPDATE driver_application SET serving_marker=NULL,open_marker=1 WHERE gate_hash=? AND id=?", gate, serving.id.toString());
        }
        return next;
    }

    private long reencrypt(String actor, Row row, VehicleFields fields, Instant now) {
        long next = row.version + 1;
        byte[] vehicle = encryptVehicle(actor, row.id, next, now, fields);
        try {
            int changed = jdbc.update("UPDATE driver_application SET version=?,key_id=?,vehicle_cipher=?,updated_at=? WHERE gate_hash=? AND id=? AND version=?",
                    next, crypto.keyId(), vehicle, sql(now), gate, row.id.toString(), row.version);
            if (changed != 1) throw unavailable();
        } finally { Arrays.fill(vehicle, (byte) 0); }
        return next;
    }

    /** Explicitly scheduled by the runtime owner; retains request markers so nothing is reapplied. */
    public int eraseExpiredResponseSecrets() {
        return maintenance.execute(status -> jdbc.update(
                "UPDATE driver_application_command SET response_cipher=NULL WHERE gate_hash=? AND response_cipher IS NOT NULL AND expires_at<=?",
                gate, sql(clock.instant())));
    }

    private Outcome replay(UUID account, String request, String payload, Instant now) {
        var rows = jdbc.query("SELECT payload_mac,key_id,response_cipher,created_at,expires_at FROM driver_application_command WHERE gate_hash=? AND request_mac=? FOR UPDATE",
                (rs, n) -> new CommandRow(rs.getString("payload_mac"), rs.getString("key_id"), rs.getBytes("response_cipher"),
                        instant(rs.getObject("created_at", LocalDateTime.class)), instant(rs.getObject("expires_at", LocalDateTime.class))),
                gate, request);
        if (rows.isEmpty()) return null;
        CommandRow previous = rows.getFirst();
        if (!AdmissionCrypto.equal(previous.payloadMac, payload)) return new Outcome(null, AdmissionException.Code.IDEMPOTENCY_KEY_REUSED);
        if (previous.cipher == null || !now.isBefore(previous.expiresAt)) {
            jdbc.update("UPDATE driver_application_command SET response_cipher=NULL WHERE gate_hash=? AND request_mac=? AND response_cipher IS NOT NULL",
                    gate, request);
            return new Outcome(null, AdmissionException.Code.REPLAY_EXPIRED);
        }
        if (!crypto.keyId().equals(previous.keyId)) throw unavailable();
        byte[] plain = crypto.decrypt(previous.cipher, commandAad(account.toString(), request, payload, previous));
        try { return new Outcome(decodeResponse(account, plain), null); }
        finally { Arrays.fill(plain, (byte) 0); }
    }

    private void remember(String actor, String request, String payload, ApplicationView view, Instant now) {
        Instant expires = now.plus(responseLifetime);
        CommandRow created = new CommandRow(payload, crypto.keyId(), null, now, expires);
        byte[] plain = encodeResponse(view);
        byte[] cipher;
        try { cipher = crypto.encrypt(plain, commandAad(actor, request, payload, created)); }
        finally { Arrays.fill(plain, (byte) 0); }
        try {
            jdbc.update("INSERT INTO driver_application_command (request_mac,gate_hash,account_id,payload_mac,key_id,response_cipher,created_at,expires_at) VALUES (?,?,?,?,?,?,?,?)",
                    request, gate, actor, payload, crypto.keyId(), cipher, sql(now), sql(expires));
        } finally { Arrays.fill(cipher, (byte) 0); }
    }

    private Row openApplication(UUID account) {
        return single(jdbc.query("SELECT id,requested_category,state,version,key_id,vehicle_cipher,created_at,updated_at FROM driver_application WHERE gate_hash=? AND account_id=? AND open_marker=1 FOR UPDATE",
                (rs, n) -> row(rs), gate, account.toString()));
    }
    /** R16: the approved file still serving while a change of situation is examined, or null. */
    private Row servingApplication(UUID account) {
        return single(jdbc.query("SELECT id,requested_category,state,version,key_id,vehicle_cipher,created_at,updated_at FROM driver_application WHERE gate_hash=? AND account_id=? AND serving_marker=1 FOR UPDATE",
                (rs, n) -> row(rs), gate, account.toString()));
    }
    /** The file the driver works with: the serving one during a change of situation, otherwise the open one. */
    private Row profileRow(UUID account) {
        Row serving = servingApplication(account);
        return serving != null ? serving : openApplication(account);
    }
    private Row owned(UUID account, UUID id) {
        return single(jdbc.query("SELECT id,requested_category,state,version,key_id,vehicle_cipher,created_at,updated_at FROM driver_application WHERE gate_hash=? AND id=? AND account_id=? FOR UPDATE",
                (rs, n) -> row(rs), gate, id.toString(), account.toString()));
    }
    private static Row row(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Row(UUID.fromString(rs.getString("id")), rs.getString("requested_category"), rs.getString("state"),
                rs.getLong("version"), rs.getString("key_id"), rs.getBytes("vehicle_cipher"),
                instant(rs.getObject("created_at", LocalDateTime.class)), instant(rs.getObject("updated_at", LocalDateTime.class)));
    }
    private static Row single(List<Row> rows) {
        if (rows.size() > 1) throw unavailable();
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private ApplicationView current(UUID account, Row row, Instant serverTime) {
        if (!crypto.keyId().equals(row.keyId)) throw unavailable();
        byte[] plain = crypto.decrypt(row.cipher, vehicleAad(account.toString(), row.id, row.version, row.updatedAt, row.keyId));
        try {
            VehicleFields fields = VehicleFields.read(new DataInputStream(new ByteArrayInputStream(plain)));
            return view(row.id, account, row.category, row.state, row.version, fields, row.createdAt, row.updatedAt, serverTime, false);
        } catch (IOException hidden) { throw unavailable(); }
        finally { Arrays.fill(plain, (byte) 0); }
    }
    private byte[] encryptVehicle(String actor, UUID id, long version, Instant updated, VehicleFields fields) {
        byte[] plain = encodeVehicle(fields);
        try { return crypto.encrypt(plain, vehicleAad(actor, id, version, updated, crypto.keyId())); }
        finally { Arrays.fill(plain, (byte) 0); }
    }
    private static byte[] encodeVehicle(VehicleFields fields) {
        try {
            var bytes = new ByteArrayOutputStream(); var output = new DataOutputStream(bytes);
            fields.write(output); output.flush(); return bytes.toByteArray();
        } catch (IOException hidden) { throw unavailable(); }
    }
    private byte[] vehicleAad(String actor, UUID id, long version, Instant updated, String keyId) {
        return AdmissionCrypto.aad("waka-admission-vehicle-v1", gate, actor, id.toString(), Long.toString(version),
                updated.toString(), keyId);
    }
    private byte[] commandAad(String actor, String request, String payload, CommandRow row) {
        return AdmissionCrypto.aad("waka-admission-response-v1", gate, actor, request, payload,
                row.keyId, row.createdAt.toString(), row.expiresAt.toString());
    }

    private static ApplicationView view(UUID id, UUID account, String category, String state, long version,
            VehicleFields fields, Instant created, Instant updated, Instant server, boolean replay) {
        return new ApplicationView(id, account, category, state, version, fields, fields.missing(), created, updated, server, replay);
    }
    private static byte[] encodeResponse(ApplicationView view) {
        try {
            var bytes = new ByteArrayOutputStream(); var output = new DataOutputStream(bytes);
            output.writeUTF(view.applicationId.toString()); output.writeUTF(view.requestedCategory);
            output.writeUTF(view.state); output.writeLong(view.version);
            view.vehicleFields.write(output);
            output.writeUTF(view.createdAt.toString()); output.writeUTF(view.updatedAt.toString());
            output.writeUTF(view.serverTime.toString());
            output.flush(); return bytes.toByteArray();
        } catch (IOException hidden) { throw unavailable(); }
    }
    private static ApplicationView decodeResponse(UUID account, byte[] plain) {
        try {
            var input = new DataInputStream(new ByteArrayInputStream(plain));
            UUID id = UUID.fromString(input.readUTF());
            String category = input.readUTF(), state = input.readUTF();
            long version = input.readLong();
            VehicleFields fields = VehicleFields.read(input);
            Instant created = Instant.parse(input.readUTF()), updated = Instant.parse(input.readUTF()),
                    server = Instant.parse(input.readUTF());
            if (input.available() != 0 || version < 1 || version > MAX_VERSION || !STATES.contains(state)
                    || !("taxi".equals(category) || "private".equals(category))) throw unavailable();
            return view(id, account, category, state, version, fields, created, updated, server, true);
        } catch (IOException | IllegalArgumentException hidden) { throw unavailable(); }
    }

    private static ApplicationView unwrap(Outcome outcome) {
        if (outcome.refusal != null) throw new AdmissionException(outcome.refusal);
        return outcome.view;
    }
    private static LocalDateTime sql(Instant value) { return LocalDateTime.ofInstant(value, ZoneOffset.UTC); }
    private static Instant instant(LocalDateTime value) { return value.toInstant(ZoneOffset.UTC); }
    private static AdmissionException invalid() { return new AdmissionException(AdmissionException.Code.INVALID_REQUEST); }
    private static AdmissionException unavailable() { return new AdmissionException(AdmissionException.Code.SERVICE_UNAVAILABLE); }
    @Override public String toString() { return "PersistentDriverApplicationService[REDACTED]"; }
}
