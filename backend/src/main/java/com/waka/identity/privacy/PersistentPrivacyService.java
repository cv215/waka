package com.waka.identity.privacy;

import com.waka.identity.profile.ProfileCrypto;
import com.waka.identity.session.PersistentSessionService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * A06 privacy requests and A07 own activity. Receiving a deletion request deletes nothing and
 * promises nothing: handling, exceptions and retention are still to be qualified (access and retention
 * rules, A05). Construct explicitly with dedicated keys; not assembled by default.
 */
public final class PersistentPrivacyService {
    public enum Kind { ACCESS, DELETION }
    /** {@code created} is false when a replay or an already open request of the same kind is returned. */
    public record RequestView(UUID requestId, String kind, String state, Instant receivedAt, boolean created) { }
    public record ActivityItem(String objectType, UUID objectId, String state, Instant at) { }
    public record ActivityPage(List<ActivityItem> items, String nextCursor) { }
    private record Outcome<T>(T value, PrivacyException.Code refusal) { }

    private final PersistentSessionService sessions;
    private final JdbcTemplate jdbc;
    private final ProfileCrypto crypto;
    private final String gate;

    public PersistentPrivacyService(PersistentSessionService sessions, JdbcTemplate jdbc, ProfileCrypto crypto, Clock clock) {
        this.sessions = Objects.requireNonNull(sessions);
        this.jdbc = Objects.requireNonNull(jdbc);
        this.crypto = Objects.requireNonNull(crypto);
        if (!sessions.usesDataSource(jdbc.getDataSource()) || !sessions.usesClock(Objects.requireNonNull(clock))) {
            throw new IllegalArgumentException("PRIVACY_DEPENDENCIES_MISMATCH");
        }
        if (crypto.reusesSessionKey(sessions)) throw new IllegalArgumentException("PRIVACY_SESSION_KEY_REUSE");
        gate = sessions.identityGateHash();
    }

    /**
     * Same key and same request: the same answer. A second request of a kind already open returns the
     * open one (its details are not replaced); the later text is not stored.
     */
    public RequestView request(char[] token, String client, UUID key, Kind kind, String details) {
        if (key == null || kind == null) throw new PrivacyException(PrivacyException.Code.INVALID_REQUEST);
        String text = PrivacyDetails.normalize(details);
        String kindName = kind.name().toLowerCase();
        return unwrap(sessions.withAuthenticatedSession(token, client, session -> {
            String actor = session.accountId().toString();
            String request = crypto.requestMac(actor, "privacy-request-v1", key.toString());
            String payload = crypto.requestMac(actor, "privacy-payload-v1",
                    kindName + "|" + (text == null ? "null" : "string:" + text));
            var previous = jdbc.query("SELECT payload_mac,request_id FROM privacy_request_command WHERE gate_hash=? AND request_mac=? FOR UPDATE",
                    (rs, n) -> new String[] { rs.getString(1), rs.getString(2) }, gate, request);
            if (!previous.isEmpty()) {
                if (!MessageDigest.isEqual(previous.getFirst()[0].getBytes(StandardCharsets.US_ASCII),
                        payload.getBytes(StandardCharsets.US_ASCII))) {
                    return new Outcome<RequestView>(null, PrivacyException.Code.IDEMPOTENCY_KEY_REUSED);
                }
                return new Outcome<>(view(actor, previous.getFirst()[1], false), null);
            }
            Instant now = session.serverTime().truncatedTo(ChronoUnit.MICROS);
            var open = jdbc.query("SELECT id FROM privacy_request WHERE gate_hash=? AND account_id=? AND kind=? AND open_marker=1 FOR UPDATE",
                    (rs, n) -> rs.getString(1), gate, actor, kindName);
            String id;
            boolean created = open.isEmpty();
            if (created) {
                id = UUID.randomUUID().toString();
                byte[] cipher = text == null ? null
                        : crypto.encrypt(text.getBytes(StandardCharsets.UTF_8), detailsAad(actor, id, crypto.keyId()));
                jdbc.update("INSERT INTO privacy_request (gate_hash,id,account_id,kind,state,open_marker,key_id,details_cipher,received_at,updated_at) VALUES (?,?,?,?,'received',1,?,?,?,?)",
                        gate, id, actor, kindName, crypto.keyId(), cipher, sql(now), sql(now));
            } else {
                id = open.getFirst();
            }
            jdbc.update("INSERT INTO privacy_request_command (gate_hash,request_mac,account_id,payload_mac,request_id,created_at) VALUES (?,?,?,?,?,?)",
                    gate, request, actor, payload, id, sql(now));
            return new Outcome<>(view(actor, id, created), null);
        }));
    }

    /**
     * A07: the account's own driver application, privacy requests and quotes, newest first. Read only:
     * nothing is assigned, ordered or extended by reading. {@code cursor} is the one returned by the
     * previous page.
     */
    public ActivityPage activity(char[] token, String client, String cursor, int limit) {
        if (limit < 1 || limit > 50) throw new PrivacyException(PrivacyException.Code.INVALID_REQUEST);
        LocalDateTime beforeAt;
        String beforeId;
        if (cursor == null) { beforeAt = null; beforeId = null; }
        else {
            if (!cursor.matches("[0-9]{1,19}_[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
                throw new PrivacyException(PrivacyException.Code.INVALID_REQUEST);
            }
            int split = cursor.indexOf('_');
            long micros;
            try { micros = Long.parseLong(cursor.substring(0, split)); }
            catch (NumberFormatException hidden) { throw new PrivacyException(PrivacyException.Code.INVALID_REQUEST); }
            beforeAt = sql(Instant.EPOCH.plus(micros, ChronoUnit.MICROS));
            beforeId = cursor.substring(split + 1);
        }
        return unwrap(sessions.withAuthenticatedSession(token, client, session -> {
            String actor = session.accountId().toString();
            LocalDateTime now = sql(session.serverTime());
            var sql = new StringBuilder("SELECT object_type,object_id,state,at FROM ("
                    + "SELECT 'driver_application' AS object_type,id AS object_id,state,updated_at AS at FROM driver_application WHERE gate_hash=? AND account_id=? "
                    + "UNION ALL SELECT 'privacy_request',id,state,updated_at FROM privacy_request WHERE gate_hash=? AND account_id=? "
                    + "UNION ALL SELECT 'quote',id,CASE WHEN expires_at>? THEN 'valid' ELSE 'expired' END,created_at FROM quote WHERE gate_hash=? AND account_id=?"
                    + ") t");
            var args = new ArrayList<Object>(List.of(gate, actor, gate, actor, now, gate, actor));
            if (beforeAt != null) {
                sql.append(" WHERE t.at<? OR (t.at=? AND t.object_id<?)");
                args.addAll(List.of(beforeAt, beforeAt, beforeId));
            }
            sql.append(" ORDER BY t.at DESC,t.object_id DESC LIMIT ").append(limit + 1);
            var rows = jdbc.query(sql.toString(), (rs, n) -> new ActivityItem(rs.getString(1), UUID.fromString(rs.getString(2)),
                    rs.getString(3), instant(rs.getObject(4, LocalDateTime.class))), args.toArray());
            boolean more = rows.size() > limit;
            var items = List.copyOf(more ? rows.subList(0, limit) : rows);
            String next = null;
            if (more) {
                ActivityItem last = items.getLast();
                next = ChronoUnit.MICROS.between(Instant.EPOCH, last.at()) + "_" + last.objectId();
            }
            return new Outcome<>(new ActivityPage(items, next), null);
        }));
    }

    private RequestView view(String actor, String id, boolean created) {
        return jdbc.queryForObject("SELECT id,kind,state,received_at FROM privacy_request WHERE gate_hash=? AND account_id=? AND id=?",
                (rs, n) -> new RequestView(UUID.fromString(rs.getString(1)), rs.getString(2), rs.getString(3),
                        instant(rs.getObject(4, LocalDateTime.class)), created), gate, actor, id);
    }
    private byte[] detailsAad(String actor, String id, String keyId) {
        return ProfileCrypto.aad("waka-privacy-details-v1", gate, actor, id, keyId);
    }
    private static <T> T unwrap(Outcome<T> outcome) {
        if (outcome.refusal != null) throw new PrivacyException(outcome.refusal);
        return outcome.value;
    }
    private static LocalDateTime sql(Instant value) { return LocalDateTime.ofInstant(value, ZoneOffset.UTC); }
    private static Instant instant(LocalDateTime value) { return value.toInstant(ZoneOffset.UTC); }
    @Override public String toString() { return "PersistentPrivacyService[REDACTED]"; }
}
