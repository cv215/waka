package com.waka.identity.files;

import com.waka.identity.admin.AdminFunction;
import com.waka.identity.admin.PersistentAdminService;
import com.waka.identity.admission.DocumentCatalog;
import com.waka.identity.admission.PersistentDriverApplicationService;
import com.waka.identity.session.PersistentSessionService;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Catalogue documents of the owner's file (image of the paper document, cleaned and encrypted like
 * the photos). Only documents that apply to the declared category and use right are accepted. A
 * person document is reused across files; a vehicle document follows the registration. Received is
 * never accepted: examination decides. Not assembled by default.
 */
public final class DriverDocumentService {
    public record DocumentView(UUID documentId, String documentType, String reviewState, LocalDate validUntil,
            int byteSize, Instant createdAt, boolean idempotentReplay) { }
    public record Content(byte[] jpeg) {
        @Override public String toString() { return "Content[REDACTED]"; }
    }
    private record Outcome<T>(T value, FileException.Code refusal) { }
    private final PersistentSessionService sessions;
    private final PersistentDriverApplicationService applications;
    private final JdbcTemplate jdbc;
    private final PrivateFileStore store;
    private final PersistentAdminService admin;
    private final String gate;

    public DriverDocumentService(PersistentSessionService sessions, PersistentDriverApplicationService applications,
            JdbcTemplate jdbc, PrivateFileStore store, PersistentAdminService admin) {
        this.sessions = Objects.requireNonNull(sessions);
        this.applications = Objects.requireNonNull(applications);
        this.jdbc = Objects.requireNonNull(jdbc);
        this.store = Objects.requireNonNull(store);
        this.admin = admin;
        if (!sessions.usesDataSource(jdbc.getDataSource())) throw new IllegalArgumentException("FILES_DEPENDENCIES_MISMATCH");
        if (store.reusesSessionKey(sessions)) throw new IllegalArgumentException("FILES_SESSION_KEY_REUSE");
        this.gate = sessions.identityGateHash();
    }

    /**
     * Replaces the current document of this type (and vehicle) with a new received one. {@code validUntil}
     * is the end date printed on the document, declared by the candidate and checked by the agent.
     */
    public DocumentView upload(char[] token, String client, UUID applicationId, String documentType, UUID key,
            String declaredType, byte[] received, LocalDate validUntil) {
        DocumentCatalog.Requirement requirement = documentType == null ? null : applications.requirement(documentType);
        if (applicationId == null || key == null || requirement == null) throw new FileException(FileException.Code.INVALID_REQUEST);
        PhotoSanitizer.Sanitized clean = PhotoSanitizer.sanitize(received, declaredType);
        UUID fileId = UUID.randomUUID();
        store.write(fileId, gate, clean.jpeg());
        boolean kept = false;
        try {
            Outcome<DocumentView> outcome = sessions.withAuthenticatedSession(token, client, session -> {
                String actor = session.accountId().toString();
                Instant now = session.serverTime();
                String request = store.mac("driver-document-request-v1", actor, key.toString());
                String payload = store.mac("driver-document-payload-v1", actor, applicationId.toString(), documentType,
                        validUntil == null ? "" : validUntil.toString(), store.contentMac("driver-document-received-v1", received));
                var previous = jdbc.query("SELECT id,document_type,review_state,valid_until,byte_size,created_at,payload_mac FROM driver_document WHERE gate_hash=? AND request_mac=? FOR UPDATE",
                        (rs, n) -> new Object[] { view(rs, true), rs.getString("payload_mac") }, gate, request);
                if (!previous.isEmpty()) {
                    if (!payload.equals(previous.getFirst()[1])) return new Outcome<DocumentView>(null, FileException.Code.IDEMPOTENCY_KEY_REUSED);
                    return new Outcome<>((DocumentView) previous.getFirst()[0], null);
                }
                var context = applications.requirementContextLocked(session.accountId(), applicationId, now);
                if (context == null) return new Outcome<DocumentView>(null, FileException.Code.NOT_FOUND);
                if (!PersistentDriverApplicationService.EDITABLE.contains(context.state())) {
                    return new Outcome<DocumentView>(null, FileException.Code.NOT_EDITABLE);
                }
                // Only what applies to this situation: no taxi title for a personal car, no use
                // authorization when the driver declared holding the vehicle.
                if (!requirement.appliesTo(context.category(), context.usageRight())) {
                    return new Outcome<DocumentView>(null, FileException.Code.NOT_APPLICABLE);
                }
                String vehicleKey = requirement.scope() == DocumentCatalog.Scope.PERSON
                        ? PersistentDriverApplicationService.PERSON_KEY : context.vehicleKey();
                if (vehicleKey == null) return new Outcome<DocumentView>(null, FileException.Code.VEHICLE_REQUIRED);
                if (validUntil != null && validUntil.isBefore(LocalDate.ofInstant(now, ZoneId.of("Africa/Douala")))) {
                    return new Outcome<DocumentView>(null, FileException.Code.EXPIRED);
                }
                jdbc.update("UPDATE driver_document SET current_marker=NULL,superseded_at=? WHERE gate_hash=? AND account_id=? AND document_type=? AND vehicle_key=? AND current_marker=1",
                        sql(now), gate, actor, documentType, vehicleKey);
                jdbc.update("INSERT INTO driver_document (gate_hash,id,account_id,document_type,vehicle_key,current_marker,review_state,valid_until,byte_size,key_id,request_mac,payload_mac,created_at) VALUES (?,?,?,?,?,1,'received',?,?,?,?,?,?)",
                        gate, fileId.toString(), actor, documentType, vehicleKey, validUntil, clean.jpeg().length,
                        store.keyId(), request, payload, sql(now));
                return new Outcome<>(new DocumentView(fileId, documentType, "received", validUntil, clean.jpeg().length, now, false), null);
            });
            if (outcome.refusal != null) throw new FileException(outcome.refusal);
            kept = !outcome.value.idempotentReplay();
            return outcome.value;
        } finally {
            Arrays.fill(clean.jpeg(), (byte) 0);
            if (!kept) store.deleteQuietly(fileId);
        }
    }

    /** The owner reads their own documents, current or replaced. */
    public Content content(char[] token, String client, UUID documentId) {
        if (documentId == null) throw new FileException(FileException.Code.INVALID_REQUEST);
        Outcome<UUID> outcome = sessions.withAuthenticatedSession(token, client, session -> {
            var rows = jdbc.query("SELECT id FROM driver_document WHERE gate_hash=? AND id=? AND account_id=?",
                    (rs, n) -> UUID.fromString(rs.getString(1)), gate, documentId.toString(), session.accountId().toString());
            return rows.isEmpty() ? new Outcome<UUID>(null, FileException.Code.NOT_FOUND) : new Outcome<>(rows.getFirst(), null);
        });
        if (outcome.refusal != null) throw new FileException(outcome.refusal);
        return new Content(store.read(outcome.value, gate));
    }

    /**
     * ADMISSION agent: only documents of a candidate who has at least one file already sent to
     * examination, never the agent's own; every read is written to the audit journal.
     */
    public Content examinationContent(char[] token, String client, char[] adminToken, UUID documentId) {
        if (admin == null) throw new FileException(FileException.Code.SERVICE_UNAVAILABLE);
        if (documentId == null) throw new FileException(FileException.Code.INVALID_REQUEST);
        Outcome<UUID> outcome = admin.withAdmin(token, client, adminToken, AdminFunction.ADMISSION, (session, context) -> {
            var rows = jdbc.query("SELECT d.account_id FROM driver_document d WHERE d.gate_hash=? AND d.id=? AND EXISTS "
                    + "(SELECT 1 FROM driver_application a WHERE a.gate_hash=d.gate_hash AND a.account_id=d.account_id AND a.state<>'draft')",
                    (rs, n) -> rs.getString(1), gate, documentId.toString());
            if (rows.isEmpty() || rows.getFirst().equals(context.accountId().toString())) {
                return new Outcome<UUID>(null, FileException.Code.NOT_FOUND);
            }
            jdbc.update("INSERT INTO audit_event (occurred_at,event_type,actor_ref,actor_scope,object_type,object_ref,reason_code,outcome,correlation_ref) VALUES (?,?,?,?,?,?,?,?,?)",
                    sql(session.serverTime()), "PRIVATE_FILE_READ", context.accountId().toString(), "ADMISSION", "driver_document",
                    documentId.toString(), "EXAMINATION", "GRANTED", context.adminSessionId().toString());
            return new Outcome<>(documentId, null);
        }, code -> new Outcome<UUID>(null, switch (code) {
            case NOT_AUTHORIZED -> FileException.Code.NOT_AUTHORIZED;
            case INVALID_ADMIN_SESSION -> FileException.Code.INVALID_ADMIN_SESSION;
            default -> FileException.Code.SERVICE_UNAVAILABLE;
        }));
        if (outcome.refusal != null) throw new FileException(outcome.refusal);
        return new Content(store.read(outcome.value, gate));
    }

    private static DocumentView view(java.sql.ResultSet rs, boolean replay) throws java.sql.SQLException {
        return new DocumentView(UUID.fromString(rs.getString("id")), rs.getString("document_type"), rs.getString("review_state"),
                rs.getObject("valid_until", LocalDate.class), rs.getInt("byte_size"),
                rs.getObject("created_at", LocalDateTime.class).toInstant(ZoneOffset.UTC), replay);
    }
    private static LocalDateTime sql(Instant value) { return LocalDateTime.ofInstant(value, ZoneOffset.UTC); }
    @Override public String toString() { return "DriverDocumentService[REDACTED]"; }
}
