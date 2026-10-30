package com.waka.mobility.incident;

import com.waka.identity.admin.AdminException;
import com.waka.identity.admin.AdminFunction;
import com.waka.identity.admin.PersistentAdminService;
import com.waka.identity.profile.ProfileCrypto;
import com.waka.identity.session.PersistentSessionService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * R12 incidents (A23-A25, A39) on a voyage reservation. Only its client and its driver declare, read and
 * answer; neither sees the other's identity. ASSISTANCE decides with a reason code; a party may contest,
 * which reopens the examination. Texts are encrypted. A decision moves no money. Not assembled by default.
 */
public final class PersistentIncidentService {
    public static final Set<String> CATEGORIES = Set.of("no_show_dispute", "safety", "behaviour", "payment", "vehicle", "other");
    public static final Set<String> DECISIONS = Set.of("no_action", "refund_review", "driver_warning", "account_review");
    public static final int MAX_TEXT = 1000;
    public record Message(String kind, String authorRole, String decision, String reasonCode, String text, Instant at) {
        @Override public String toString() { return "IncidentMessage[REDACTED]"; }
    }
    /** {@code viewerRole} is client, driver or assistance. */
    public record IncidentView(UUID incidentId, UUID reservationId, String category, String state, long version,
            String viewerRole, Instant createdAt, List<Message> messages, boolean idempotentReplay) {
        @Override public String toString() { return "IncidentView[REDACTED]"; }
    }
    private record Out<T>(T value, IncidentException.Code refusal) { }
    private record Row(String id, String reservation, String reporter, String client, String driver, String category, String state,
            long version, Instant createdAt) { }

    private final PersistentSessionService sessions;
    private final PersistentAdminService admin;
    private final JdbcTemplate jdbc;
    private final ProfileCrypto crypto;
    private final String gate;

    public PersistentIncidentService(PersistentSessionService sessions, PersistentAdminService admin, JdbcTemplate jdbc,
            ProfileCrypto crypto, Clock clock) {
        this.sessions = Objects.requireNonNull(sessions);
        this.admin = Objects.requireNonNull(admin);
        this.jdbc = Objects.requireNonNull(jdbc);
        this.crypto = Objects.requireNonNull(crypto);
        if (!sessions.usesDataSource(jdbc.getDataSource()) || !sessions.usesClock(Objects.requireNonNull(clock))) {
            throw new IllegalArgumentException("INCIDENT_DEPENDENCIES_MISMATCH");
        }
        if (crypto.reusesSessionKey(sessions)) throw new IllegalArgumentException("INCIDENT_SESSION_KEY_REUSE");
        gate = sessions.identityGateHash();
    }

    /** A23: one incident per reservation and reporter; a new report with another key finds the existing one. */
    public IncidentView report(char[] token, String client, UUID key, UUID reservationId, String category, String text) {
        String body = text(text);
        if (key == null || reservationId == null || !CATEGORIES.contains(category)) throw invalid();
        return unwrap(sessions.withAuthenticatedSession(token, client, session -> {
            String actor = session.accountId().toString();
            String request = crypto.requestMac(actor, "incident-report-v1", key.toString());
            String payload = crypto.requestMac(actor, "incident-report-payload-v1", reservationId + "|" + category + "|" + body);
            var previous = jdbc.query("SELECT id,payload_mac FROM incident WHERE gate_hash=? AND request_mac=? FOR UPDATE",
                    (rs, n) -> new String[] { rs.getString(1), rs.getString(2) }, gate, request);
            if (!previous.isEmpty()) {
                if (!same(previous.getFirst()[1], payload)) return new Out<IncidentView>(null, IncidentException.Code.IDEMPOTENCY_KEY_REUSED);
                return new Out<>(view(row(previous.getFirst()[0]), actor, true), null);
            }
            var parties = jdbc.query("SELECT r.client_account_id,v.driver_account_id FROM voyage_reservation r JOIN voyage v "
                    + "ON v.gate_hash=r.gate_hash AND v.id=r.voyage_id WHERE r.gate_hash=? AND r.id=? FOR UPDATE",
                    (rs, n) -> new String[] { rs.getString(1), rs.getString(2) }, gate, reservationId.toString());
            if (parties.isEmpty() || !(actor.equals(parties.getFirst()[0]) || actor.equals(parties.getFirst()[1]))) {
                return new Out<IncidentView>(null, IncidentException.Code.NOT_FOUND);
            }
            var existing = jdbc.query("SELECT id FROM incident WHERE gate_hash=? AND reservation_id=? AND reporter_account_id=?",
                    (rs, n) -> rs.getString(1), gate, reservationId.toString(), actor);
            if (!existing.isEmpty()) return new Out<>(view(row(existing.getFirst()), actor, true), null);
            Instant now = session.serverTime().truncatedTo(ChronoUnit.MICROS);
            String id = UUID.randomUUID().toString();
            String role = actor.equals(parties.getFirst()[0]) ? "client" : "driver";
            jdbc.update("INSERT INTO incident (gate_hash,id,reservation_id,reporter_account_id,reporter_role,client_account_id,driver_account_id,category,state,open_marker,version,request_mac,payload_mac,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,'open',1,1,?,?,?,?)",
                    gate, id, reservationId.toString(), actor, role, parties.getFirst()[0], parties.getFirst()[1], category, request, payload, sql(now), sql(now));
            addMessage(id, actor, role, "report", null, null, body, request, payload, now);
            return new Out<>(view(row(id), actor, false), null);
        }));
    }

    /** The caller's incidents as client or driver, newest first. */
    public List<IncidentView> mine(char[] token, String client) {
        return unwrap(sessions.withAuthenticatedSession(token, client, session -> {
            String actor = session.accountId().toString();
            return new Out<>(jdbc.query("SELECT id FROM incident WHERE gate_hash=? AND (client_account_id=? OR driver_account_id=?) ORDER BY updated_at DESC,id LIMIT 50",
                    (rs, n) -> rs.getString(1), gate, actor, actor).stream().map(id -> view(row(id), actor, false)).toList(), null);
        }));
    }

    /** A25: kind reply or contest; contest only after a decision, and it reopens the examination. */
    public IncidentView message(char[] token, String client, UUID key, UUID incidentId, String kind, String text) {
        String body = text(text);
        if (key == null || incidentId == null || !("reply".equals(kind) || "contest".equals(kind))) throw invalid();
        return unwrap(sessions.withAuthenticatedSession(token, client, session -> {
            String actor = session.accountId().toString();
            String request = crypto.requestMac(actor, "incident-message-v1", key.toString());
            String payload = crypto.requestMac(actor, "incident-message-payload-v1", incidentId + "|" + kind + "|" + body);
            var previous = jdbc.query("SELECT incident_id,payload_mac FROM incident_message WHERE gate_hash=? AND request_mac=?",
                    (rs, n) -> new String[] { rs.getString(1), rs.getString(2) }, gate, request);
            if (!previous.isEmpty()) {
                if (!same(previous.getFirst()[1], payload)) return new Out<IncidentView>(null, IncidentException.Code.IDEMPOTENCY_KEY_REUSED);
                return new Out<>(view(row(previous.getFirst()[0]), actor, true), null);
            }
            Row incident = lockedRow(incidentId);
            if (incident == null || !(actor.equals(incident.client) || actor.equals(incident.driver))) {
                return new Out<IncidentView>(null, IncidentException.Code.NOT_FOUND);
            }
            if ("contest".equals(kind) && !"decided".equals(incident.state)) return new Out<IncidentView>(null, IncidentException.Code.NOT_DECIDED);
            Instant now = session.serverTime().truncatedTo(ChronoUnit.MICROS);
            String role = actor.equals(incident.client) ? "client" : "driver";
            addMessage(incident.id, actor, role, kind, null, null, body, request, payload, now);
            jdbc.update("UPDATE incident SET state=?,version=version+1,updated_at=? WHERE gate_hash=? AND id=?",
                    "contest".equals(kind) ? "contested" : incident.state, sql(now), gate, incident.id);
            return new Out<>(view(row(incident.id), actor, false), null);
        }));
    }

    // ---- ASSISTANCE (A39) ----

    /** Open and contested incidents first, oldest first; never those where the agent is a party. */
    public List<IncidentView> queue(char[] token, String client, char[] adminToken) {
        return unwrap(admin.withAdmin(token, client, adminToken, AdminFunction.ASSISTANCE, (session, context) -> {
            String agent = context.accountId().toString();
            return new Out<>(jdbc.query("SELECT id FROM incident WHERE gate_hash=? AND client_account_id<>? AND driver_account_id<>? "
                    + "ORDER BY state='decided',updated_at,id LIMIT 100", (rs, n) -> rs.getString(1), gate, agent, agent)
                    .stream().map(id -> view(row(id), null, false)).toList(), null);
        }, code -> new Out<List<IncidentView>>(null, refused(code))));

    }

    /** A39: a reasoned decision on the version read; the text is optional and read by both parties. */
    public IncidentView decide(char[] token, String client, char[] adminToken, UUID key, UUID incidentId, long expectedVersion,
            String decision, String reasonCode, String text) {
        String body = text == null ? null : text(text);
        if (key == null || incidentId == null || expectedVersion < 1 || !DECISIONS.contains(decision)
                || reasonCode == null || !reasonCode.matches("[A-Z][A-Z0-9_]{2,47}")) throw invalid();
        return unwrap(admin.withAdmin(token, client, adminToken, AdminFunction.ASSISTANCE, (session, context) -> {
            String agent = context.accountId().toString();
            String request = crypto.requestMac(agent, "incident-decision-v1", key.toString());
            String payload = crypto.requestMac(agent, "incident-decision-payload-v1",
                    incidentId + "|" + expectedVersion + "|" + decision + "|" + reasonCode + "|" + (body == null ? "" : body));
            var previous = jdbc.query("SELECT incident_id,payload_mac FROM incident_message WHERE gate_hash=? AND request_mac=?",
                    (rs, n) -> new String[] { rs.getString(1), rs.getString(2) }, gate, request);
            if (!previous.isEmpty()) {
                if (!same(previous.getFirst()[1], payload)) return new Out<IncidentView>(null, IncidentException.Code.IDEMPOTENCY_KEY_REUSED);
                return new Out<>(view(row(previous.getFirst()[0]), null, true), null);
            }
            Row incident = lockedRow(incidentId);
            if (incident == null) return new Out<IncidentView>(null, IncidentException.Code.NOT_FOUND);
            if (agent.equals(incident.client) || agent.equals(incident.driver)) return new Out<IncidentView>(null, IncidentException.Code.CONFLICT_OF_INTEREST);
            if (incident.version != expectedVersion) return new Out<IncidentView>(null, IncidentException.Code.VERSION_CONFLICT);
            Instant now = session.serverTime().truncatedTo(ChronoUnit.MICROS);
            addMessage(incident.id, agent, "assistance", "decision", decision, reasonCode, body, request, payload, now);
            jdbc.update("UPDATE incident SET state='decided',version=version+1,updated_at=? WHERE gate_hash=? AND id=?", sql(now), gate, incident.id);
            jdbc.update("INSERT INTO audit_event (occurred_at,event_type,actor_ref,actor_scope,object_type,object_ref,reason_code,outcome,correlation_ref) VALUES (?,?,?,?,?,?,?,?,?)",
                    sql(now), "INCIDENT_DECIDED", agent, "ASSISTANCE", "incident", incident.id, reasonCode, decision.toUpperCase(),
                    context.adminSessionId().toString());
            return new Out<>(view(row(incident.id), null, false), null);
        }, code -> new Out<IncidentView>(null, refused(code))));

    }

    private void addMessage(String incident, String author, String role, String kind, String decision, String reason, String text,
            String request, String payload, Instant now) {
        String id = UUID.randomUUID().toString();
        byte[] cipher = text == null ? null : crypto.encrypt(text.getBytes(StandardCharsets.UTF_8), aad(incident, id, crypto.keyId()));
        // The incident row is locked by every caller, so positions are numbered one after the other.
        Integer position = jdbc.queryForObject("SELECT COUNT(*) FROM incident_message WHERE gate_hash=? AND incident_id=?", Integer.class, gate, incident);
        jdbc.update("INSERT INTO incident_message (gate_hash,id,incident_id,author_account_id,author_role,kind,decision,reason_code,key_id,text_cipher,request_mac,payload_mac,created_at,position) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                gate, id, incident, author, role, kind, decision, reason, crypto.keyId(), cipher,
                "report".equals(kind) ? crypto.requestMac(author, "incident-report-message-v1", request) : request, payload, sql(now),
                position == null ? 0 : position + 1);
    }
    private Row row(String id) {
        return jdbc.query("SELECT * FROM incident WHERE gate_hash=? AND id=?", (rs, n) -> new Row(rs.getString("id"), rs.getString("reservation_id"),
                rs.getString("reporter_account_id"), rs.getString("client_account_id"), rs.getString("driver_account_id"), rs.getString("category"),
                rs.getString("state"), rs.getLong("version"), rs.getObject("created_at", LocalDateTime.class).toInstant(ZoneOffset.UTC)),
                gate, id).getFirst();
    }
    private Row lockedRow(UUID id) {
        var ids = jdbc.query("SELECT id FROM incident WHERE gate_hash=? AND id=? FOR UPDATE", (rs, n) -> rs.getString(1), gate, id.toString());
        return ids.isEmpty() ? null : row(ids.getFirst());
    }
    /** The viewer sees roles only (client, driver, assistance), never account identifiers. */
    private IncidentView view(Row r, String viewer, boolean replay) {
        List<Message> messages = jdbc.query("SELECT id,author_role,kind,decision,reason_code,key_id,text_cipher,created_at FROM incident_message "
                + "WHERE gate_hash=? AND incident_id=? ORDER BY position,created_at,id", (rs, n) -> {
                    byte[] cipher = rs.getBytes("text_cipher");
                    String text = null;
                    if (cipher != null) {
                        if (!crypto.keyId().equals(rs.getString("key_id"))) throw new IncidentException(IncidentException.Code.SERVICE_UNAVAILABLE);
                        text = new String(crypto.decrypt(cipher, aad(r.id, rs.getString("id"), rs.getString("key_id"))), StandardCharsets.UTF_8);
                    }
                    return new Message(rs.getString("kind"), rs.getString("author_role"), rs.getString("decision"), rs.getString("reason_code"),
                            text, rs.getObject("created_at", LocalDateTime.class).toInstant(ZoneOffset.UTC));
                }, gate, r.id);
        String role = viewer == null ? "assistance" : viewer.equals(r.client) ? "client" : "driver";
        return new IncidentView(UUID.fromString(r.id), UUID.fromString(r.reservation), r.category, r.state, r.version, role,
                r.createdAt, List.copyOf(messages), replay);
    }

    /** 1 to 1 000 characters, line breaks allowed, no hidden control or format character. */
    static String text(String input) {
        if (input == null) throw invalid();
        String text = Normalizer.normalize(input, Normalizer.Form.NFC).strip();
        int count = text.codePointCount(0, text.length());
        if (count < 1 || count > MAX_TEXT) throw invalid();
        for (int i = 0; i < text.length();) {
            int point = text.codePointAt(i), type = Character.getType(point);
            if ((type == Character.CONTROL && point != '\n') || type == Character.FORMAT || type == Character.SURROGATE
                    || type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR) throw invalid();
            i += Character.charCount(point);
        }
        return text;
    }
    private byte[] aad(String incident, String message, String keyId) {
        return ProfileCrypto.aad("waka-incident-message-v1", gate, incident, message, keyId);
    }
    private static boolean same(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.US_ASCII), b.getBytes(StandardCharsets.US_ASCII));
    }
    private static IncidentException invalid() { return new IncidentException(IncidentException.Code.INVALID_REQUEST); }
    private static <T> T unwrap(Out<T> out) {
        if (out.refusal != null) throw new IncidentException(out.refusal);
        return out.value;
    }
    private static IncidentException.Code refused(AdminException.Code code) {
        return switch (code) {
            case NOT_AUTHORIZED -> IncidentException.Code.NOT_AUTHORIZED;
            case INVALID_ADMIN_SESSION -> IncidentException.Code.INVALID_ADMIN_SESSION;
            default -> IncidentException.Code.SERVICE_UNAVAILABLE;
        };
    }
    private static LocalDateTime sql(Instant value) { return LocalDateTime.ofInstant(value, ZoneOffset.UTC); }
    @Override public String toString() { return "PersistentIncidentService[REDACTED]"; }
}
