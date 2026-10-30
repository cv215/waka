package com.waka.identity.privacy;

import com.waka.identity.admin.AdminException;
import com.waka.identity.admin.AdminFunction;
import com.waka.identity.admin.PersistentAdminService;
import com.waka.identity.profile.ProfileCrypto;
import com.waka.identity.session.PersistentSessionService;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * R12: requests about personal data, handled by a PRIVACY agent. Reading the free text of a request is
 * written to the audit journal; closing it records an outcome and a reason code. Closing deletes nothing:
 * the deletion itself follows procedure A05 once retention periods are qualified. An agent never handles
 * a request of their own account. Uses the same keys as {@link PersistentPrivacyService}.
 */
public final class PrivacyAdminService {
    public record RequestItem(UUID requestId, String kind, String state, Instant receivedAt, String details,
            String outcome, String outcomeReason) {
        @Override public String toString() { return "PrivacyRequestItem[REDACTED]"; }
    }
    private record Out<T>(T value, AdminException.Code refusal) { }

    private final PersistentAdminService admin;
    private final JdbcTemplate jdbc;
    private final ProfileCrypto crypto;
    private final String gate;

    public PrivacyAdminService(PersistentSessionService sessions, PersistentAdminService admin, JdbcTemplate jdbc, ProfileCrypto crypto) {
        this.admin = Objects.requireNonNull(admin);
        this.jdbc = Objects.requireNonNull(jdbc);
        this.crypto = Objects.requireNonNull(crypto);
        if (!Objects.requireNonNull(sessions).usesDataSource(jdbc.getDataSource())) throw new IllegalArgumentException("PRIVACY_ADMIN_MISMATCH");
        gate = sessions.identityGateHash();
    }

    /** Open requests first, oldest first; the details are decrypted for the agent and the reading is audited. */
    public List<RequestItem> requests(char[] token, String client, char[] adminToken) {
        return unwrap(admin.withAdmin(token, client, adminToken, AdminFunction.PRIVACY, (session, context) -> {
            String agent = context.accountId().toString();
            var rows = jdbc.query("SELECT id,account_id,kind,state,received_at,key_id,details_cipher,outcome,outcome_reason FROM privacy_request "
                    + "WHERE gate_hash=? AND account_id<>? ORDER BY state='closed',received_at,id LIMIT 100", (rs, n) -> {
                        byte[] cipher = rs.getBytes("details_cipher");
                        String text = null;
                        if (cipher != null) {
                            if (!crypto.keyId().equals(rs.getString("key_id"))) throw new AdminException(AdminException.Code.SERVICE_UNAVAILABLE);
                            text = new String(crypto.decrypt(cipher, ProfileCrypto.aad("waka-privacy-details-v1", gate,
                                    rs.getString("account_id"), rs.getString("id"), rs.getString("key_id"))), StandardCharsets.UTF_8);
                        }
                        return new RequestItem(UUID.fromString(rs.getString("id")), rs.getString("kind"), rs.getString("state"),
                                rs.getObject("received_at", LocalDateTime.class).toInstant(ZoneOffset.UTC), text,
                                rs.getString("outcome"), rs.getString("outcome_reason"));
                    }, gate, agent);
            for (var row : rows) {
                if (row.details() != null) audit(session.serverTime(), "PRIVACY_REQUEST_READ", context, row.requestId(), "HANDLING", "GRANTED");
            }
            return new Out<>(rows, null);
        }, code -> new Out<List<RequestItem>>(null, code)));
    }

    /** Closes an open request with {@code completed} or {@code refused} and a reason code; repeating it changes nothing. */
    public RequestItem close(char[] token, String client, char[] adminToken, UUID requestId, String outcome, String reasonCode) {
        if (requestId == null || !("completed".equals(outcome) || "refused".equals(outcome))
                || reasonCode == null || !reasonCode.matches("[A-Z][A-Z0-9_]{2,47}")) {
            throw new AdminException(AdminException.Code.INVALID_REQUEST);
        }
        return unwrap(admin.withAdmin(token, client, adminToken, AdminFunction.PRIVACY, (session, context) -> {
            String agent = context.accountId().toString();
            var rows = jdbc.query("SELECT account_id,state FROM privacy_request WHERE gate_hash=? AND id=? FOR UPDATE",
                    (rs, n) -> new String[] { rs.getString(1), rs.getString(2) }, gate, requestId.toString());
            if (rows.isEmpty() || rows.getFirst()[0].equals(agent)) return new Out<RequestItem>(null, AdminException.Code.NOT_FOUND);
            if ("received".equals(rows.getFirst()[1])) {
                Instant now = session.serverTime();
                jdbc.update("UPDATE privacy_request SET state='closed',open_marker=NULL,outcome=?,outcome_reason=?,closed_by=?,updated_at=? WHERE gate_hash=? AND id=?",
                        outcome, reasonCode, agent, LocalDateTime.ofInstant(now, ZoneOffset.UTC), gate, requestId.toString());
                audit(now, "PRIVACY_REQUEST_CLOSED", context, requestId, reasonCode, outcome.toUpperCase());
            }
            var item = jdbc.query("SELECT id,kind,state,received_at,outcome,outcome_reason FROM privacy_request WHERE gate_hash=? AND id=?",
                    (rs, n) -> new RequestItem(UUID.fromString(rs.getString(1)), rs.getString(2), rs.getString(3),
                            rs.getObject(4, LocalDateTime.class).toInstant(ZoneOffset.UTC), null, rs.getString(5), rs.getString(6)),
                    gate, requestId.toString()).getFirst();
            return new Out<>(item, null);
        }, code -> new Out<RequestItem>(null, code)));
    }

    private void audit(Instant now, String event, PersistentAdminService.AdminContext context, UUID requestId, String reason, String outcome) {
        jdbc.update("INSERT INTO audit_event (occurred_at,event_type,actor_ref,actor_scope,object_type,object_ref,reason_code,outcome,correlation_ref) VALUES (?,?,?,?,?,?,?,?,?)",
                LocalDateTime.ofInstant(now, ZoneOffset.UTC), event, context.accountId().toString(), "PRIVACY", "privacy_request",
                requestId.toString(), reason, outcome, context.adminSessionId().toString());
    }
    private static <T> T unwrap(Out<T> out) {
        if (out.refusal != null) throw new AdminException(out.refusal);
        return out.value;
    }
    @Override public String toString() { return "PrivacyAdminService[REDACTED]"; }
}
