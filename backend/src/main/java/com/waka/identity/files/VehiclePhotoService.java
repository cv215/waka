package com.waka.identity.files;

import com.waka.identity.admin.AdminFunction;
import com.waka.identity.admin.PersistentAdminService;
import com.waka.identity.admission.PersistentDriverApplicationService;
import com.waka.identity.session.PersistentSessionService;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 06.04: the owner's six vehicle views on a draft candidature. Received and cleaned is never
 * approved: examination belongs to 06.08. Not assembled by default.
 */
public final class VehiclePhotoService {
    public static final List<String> VIEWS = List.of("PH01", "PH02", "PH03", "PH04", "PH05", "PH06");
    public record PhotoView(UUID fileId, UUID applicationId, String view, String state, int width, int height,
            int byteSize, Instant createdAt, boolean idempotentReplay) { }
    /** One entry per required view; a missing view has no file. */
    public record ViewStatus(String view, String state, UUID fileId, Instant createdAt) { }
    public record Content(byte[] jpeg) {
        @Override public String toString() { return "Content[REDACTED]"; }
    }
    private record Row(UUID id, UUID applicationId, String view, int width, int height, int size, String payloadMac,
            Instant createdAt, boolean current) { }
    private record Outcome<T>(T value, FileException.Code refusal) { }
    private final PersistentSessionService sessions;
    private final JdbcTemplate jdbc;
    private final PrivateFileStore store;
    private final String gate;

    private final PersistentAdminService admin;

    public VehiclePhotoService(PersistentSessionService sessions, JdbcTemplate jdbc, PrivateFileStore store) {
        this(sessions, jdbc, store, null);
    }

    /** With {@code admin}, an ADMISSION agent may also read photos of files under examination. */
    public VehiclePhotoService(PersistentSessionService sessions, JdbcTemplate jdbc, PrivateFileStore store,
            PersistentAdminService admin) {
        this.admin = admin;
        this.sessions = Objects.requireNonNull(sessions);
        this.jdbc = Objects.requireNonNull(jdbc);
        this.store = Objects.requireNonNull(store);
        if (!sessions.usesDataSource(jdbc.getDataSource())) throw new IllegalArgumentException("FILES_DEPENDENCIES_MISMATCH");
        if (store.reusesSessionKey(sessions)) throw new IllegalArgumentException("FILES_SESSION_KEY_REUSE");
        this.gate = sessions.identityGateHash();
    }

    public PhotoView upload(char[] token, String client, UUID applicationId, String view, UUID key,
            String declaredType, byte[] received) {
        if (applicationId == null || key == null || !VIEWS.contains(view)) throw new FileException(FileException.Code.INVALID_REQUEST);
        // Checked and cleaned before the session transaction: CPU work stays outside the gate,
        // and a bad file is a 400, not a 503 from the transaction wrapper.
        PhotoSanitizer.Sanitized clean = PhotoSanitizer.sanitize(received, declaredType);
        UUID fileId = UUID.randomUUID();
        store.write(fileId, gate, clean.jpeg());
        boolean kept = false;
        try {
            Outcome<PhotoView> outcome = sessions.withAuthenticatedSession(token, client, session -> {
                String actor = session.accountId().toString();
                String request = store.mac("vehicle-photo-request-v1", actor, key.toString());
                String payload = store.mac("vehicle-photo-payload-v1", actor, applicationId.toString(), view,
                        store.contentMac("vehicle-photo-received-v1", received));
                var previous = jdbc.query("SELECT id,application_id,photo_view,width,height,byte_size,payload_mac,created_at,current_marker FROM vehicle_photo WHERE gate_hash=? AND request_mac=? FOR UPDATE",
                        (rs, n) -> row(rs), gate, request);
                if (!previous.isEmpty()) {
                    Row original = previous.getFirst();
                    if (!payload.equals(original.payloadMac)) return new Outcome<PhotoView>(null, FileException.Code.IDEMPOTENCY_KEY_REUSED);
                    return new Outcome<>(view(original, true), null);
                }
                var states = jdbc.query("SELECT state FROM driver_application WHERE gate_hash=? AND id=? AND account_id=? FOR UPDATE",
                        (rs, n) -> rs.getString(1), gate, applicationId.toString(), actor);
                if (states.isEmpty()) return new Outcome<PhotoView>(null, FileException.Code.NOT_FOUND);
                if (!PersistentDriverApplicationService.EDITABLE.contains(states.getFirst())) {
                    return new Outcome<PhotoView>(null, FileException.Code.NOT_EDITABLE);
                }
                Instant now = session.serverTime();
                jdbc.update("UPDATE vehicle_photo SET current_marker=NULL,superseded_at=? WHERE gate_hash=? AND application_id=? AND photo_view=? AND current_marker=1",
                        sql(now), gate, applicationId.toString(), view);
                jdbc.update("INSERT INTO vehicle_photo (gate_hash,id,account_id,application_id,photo_view,current_marker,content_type,byte_size,width,height,key_id,request_mac,payload_mac,created_at) VALUES (?,?,?,?,?,1,'image/jpeg',?,?,?,?,?,?,?)",
                        gate, fileId.toString(), actor, applicationId.toString(), view, clean.jpeg().length, clean.width(),
                        clean.height(), store.keyId(), request, payload, sql(now));
                return new Outcome<>(new PhotoView(fileId, applicationId, view, "received", clean.width(), clean.height(),
                        clean.jpeg().length, now, false), null);
            });
            if (outcome.refusal != null) throw new FileException(outcome.refusal);
            kept = !outcome.value.idempotentReplay();
            return outcome.value;
        } finally {
            Arrays.fill(clean.jpeg(), (byte) 0);
            if (!kept) store.deleteQuietly(fileId);
        }
    }

    /** The six required views, each "missing" or "received"; never an examination result. */
    public List<ViewStatus> list(char[] token, String client, UUID applicationId) {
        if (applicationId == null) throw new FileException(FileException.Code.INVALID_REQUEST);
        Outcome<List<ViewStatus>> outcome = sessions.withAuthenticatedSession(token, client, session -> {
            String actor = session.accountId().toString();
            if (jdbc.query("SELECT id FROM driver_application WHERE gate_hash=? AND id=? AND account_id=?",
                    (rs, n) -> rs.getString(1), gate, applicationId.toString(), actor).isEmpty()) {
                return new Outcome<List<ViewStatus>>(null, FileException.Code.NOT_FOUND);
            }
            var current = jdbc.query("SELECT id,application_id,photo_view,width,height,byte_size,payload_mac,created_at,current_marker FROM vehicle_photo WHERE gate_hash=? AND application_id=? AND account_id=? AND current_marker=1",
                    (rs, n) -> row(rs), gate, applicationId.toString(), actor);
            List<ViewStatus> views = new ArrayList<>();
            for (String view : VIEWS) {
                Row row = current.stream().filter(r -> r.view.equals(view)).findFirst().orElse(null);
                views.add(row == null ? new ViewStatus(view, "missing", null, null)
                        : new ViewStatus(view, "received", row.id, row.createdAt));
            }
            return new Outcome<>(List.copyOf(views), null);
        });
        if (outcome.refusal != null) throw new FileException(outcome.refusal);
        return outcome.value;
    }

    /** A31 for the owner only; a replaced photo stays readable by its owner as history. */
    public Content content(char[] token, String client, UUID fileId) {
        if (fileId == null) throw new FileException(FileException.Code.INVALID_REQUEST);
        Outcome<UUID> outcome = sessions.withAuthenticatedSession(token, client, session -> {
            var rows = jdbc.query("SELECT id FROM vehicle_photo WHERE gate_hash=? AND id=? AND account_id=?",
                    (rs, n) -> UUID.fromString(rs.getString(1)), gate, fileId.toString(), session.accountId().toString());
            return rows.isEmpty() ? new Outcome<UUID>(null, FileException.Code.NOT_FOUND) : new Outcome<>(rows.getFirst(), null);
        });
        if (outcome.refusal != null) throw new FileException(outcome.refusal);
        // Authorization is settled; the immutable encrypted file is read outside the gate.
        return new Content(store.read(outcome.value, gate));
    }

    /**
     * A31 for an ADMISSION agent: only a photo of a file already sent to examination, never the agent's
     * own file, and every read is written to the audit journal (opaque references, no content).
     */
    public Content examinationContent(char[] token, String client, char[] adminToken, UUID fileId) {
        if (admin == null) throw new FileException(FileException.Code.SERVICE_UNAVAILABLE);
        if (fileId == null) throw new FileException(FileException.Code.INVALID_REQUEST);
        Outcome<UUID> outcome = admin.withAdmin(token, client, adminToken, AdminFunction.ADMISSION, (session, context) -> {
            var rows = jdbc.query("SELECT p.account_id FROM vehicle_photo p JOIN driver_application a ON a.gate_hash=p.gate_hash AND a.id=p.application_id "
                    + "WHERE p.gate_hash=? AND p.id=? AND a.state<>'draft'", (rs, n) -> rs.getString(1), gate, fileId.toString());
            if (rows.isEmpty() || rows.getFirst().equals(context.accountId().toString())) {
                return new Outcome<UUID>(null, FileException.Code.NOT_FOUND);
            }
            jdbc.update("INSERT INTO audit_event (occurred_at,event_type,actor_ref,actor_scope,object_type,object_ref,reason_code,outcome,correlation_ref) VALUES (?,?,?,?,?,?,?,?,?)",
                    sql(session.serverTime()), "PRIVATE_FILE_READ", context.accountId().toString(), "ADMISSION", "vehicle_photo",
                    fileId.toString(), "EXAMINATION", "GRANTED", context.adminSessionId().toString());
            return new Outcome<>(fileId, null);
        }, code -> new Outcome<UUID>(null, switch (code) {
            case NOT_AUTHORIZED -> FileException.Code.NOT_AUTHORIZED;
            case INVALID_ADMIN_SESSION -> FileException.Code.INVALID_ADMIN_SESSION;
            default -> FileException.Code.SERVICE_UNAVAILABLE;
        }));
        if (outcome.refusal != null) throw new FileException(outcome.refusal);
        return new Content(store.read(outcome.value, gate));
    }

    private static Row row(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Row(UUID.fromString(rs.getString("id")), UUID.fromString(rs.getString("application_id")),
                rs.getString("photo_view"), rs.getInt("width"), rs.getInt("height"), rs.getInt("byte_size"),
                rs.getString("payload_mac"), rs.getObject("created_at", LocalDateTime.class).toInstant(ZoneOffset.UTC),
                rs.getObject("current_marker") != null);
    }
    private static PhotoView view(Row row, boolean replay) {
        return new PhotoView(row.id, row.applicationId, row.view, row.current ? "received" : "replaced",
                row.width, row.height, row.size, row.createdAt, replay);
    }
    private static LocalDateTime sql(Instant value) { return LocalDateTime.ofInstant(value, ZoneOffset.UTC); }
    @Override public String toString() { return "VehiclePhotoService[REDACTED]"; }
}
