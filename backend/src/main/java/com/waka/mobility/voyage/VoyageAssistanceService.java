package com.waka.mobility.voyage;

import com.waka.identity.admin.AdminException;
import com.waka.identity.admin.AdminFunction;
import com.waka.identity.admin.PersistentAdminService;
import com.waka.identity.session.PersistentSessionService;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * ASSISTANCE for intercity voyages: voyages whose boarding is locked after too many wrong codes, and
 * their unlocking with a reason code, written to the audit journal. The agent never sees passengers'
 * identities or codes, and never unlocks a voyage they drive themselves.
 */
public final class VoyageAssistanceService {
    public record LockedVoyage(UUID voyageId, Instant departureAt, int failures) { }
    private record Out<T>(T value, AdminException.Code refusal) { }

    private final PersistentAdminService admin;
    private final JdbcTemplate jdbc;
    private final String gate;

    public VoyageAssistanceService(PersistentSessionService sessions, PersistentAdminService admin, JdbcTemplate jdbc) {
        this.admin = Objects.requireNonNull(admin);
        this.jdbc = Objects.requireNonNull(jdbc);
        if (!Objects.requireNonNull(sessions).usesDataSource(jdbc.getDataSource())) throw new IllegalArgumentException("VOYAGE_ASSISTANCE_MISMATCH");
        gate = sessions.identityGateHash();
    }

    public List<LockedVoyage> locked(char[] token, String client, char[] adminToken) {
        return unwrap(admin.withAdmin(token, client, adminToken, AdminFunction.ASSISTANCE, (session, context) -> new Out<>(
                jdbc.query("SELECT id,departure_at,boarding_failures FROM voyage WHERE gate_hash=? AND state='published' AND boarding_failures>=? "
                        + "AND driver_account_id<>? ORDER BY departure_at,id LIMIT 100",
                        (rs, n) -> new LockedVoyage(UUID.fromString(rs.getString(1)),
                                rs.getObject(2, LocalDateTime.class).toInstant(ZoneOffset.UTC), rs.getInt(3)),
                        gate, PersistentVoyageService.MAX_BOARDING_FAILURES, context.accountId().toString()), null),
                code -> new Out<List<LockedVoyage>>(null, code)));
    }

    /** Resets the wrong-code counter; repeating it on an unlocked voyage changes nothing. */
    public LockedVoyage unlock(char[] token, String client, char[] adminToken, UUID voyageId, String reasonCode) {
        if (voyageId == null || reasonCode == null || !reasonCode.matches("[A-Z][A-Z0-9_]{2,47}")) {
            throw new AdminException(AdminException.Code.INVALID_REQUEST);
        }
        return unwrap(admin.withAdmin(token, client, adminToken, AdminFunction.ASSISTANCE, (session, context) -> {
            var rows = jdbc.query("SELECT driver_account_id,departure_at,boarding_failures FROM voyage WHERE gate_hash=? AND id=? FOR UPDATE",
                    (rs, n) -> new Object[] { rs.getString(1), rs.getObject(2, LocalDateTime.class), rs.getInt(3) }, gate, voyageId.toString());
            if (rows.isEmpty() || rows.getFirst()[0].equals(context.accountId().toString())) return new Out<LockedVoyage>(null, AdminException.Code.NOT_FOUND);
            if ((Integer) rows.getFirst()[2] > 0) {
                jdbc.update("UPDATE voyage SET boarding_failures=0 WHERE gate_hash=? AND id=?", gate, voyageId.toString());
                jdbc.update("INSERT INTO audit_event (occurred_at,event_type,actor_ref,actor_scope,object_type,object_ref,reason_code,outcome,correlation_ref) VALUES (?,?,?,?,?,?,?,?,?)",
                        LocalDateTime.ofInstant(session.serverTime(), ZoneOffset.UTC), "VOYAGE_BOARDING_UNLOCKED", context.accountId().toString(),
                        "ASSISTANCE", "voyage", voyageId.toString(), reasonCode, "APPLIED", context.adminSessionId().toString());
            }
            return new Out<>(new LockedVoyage(voyageId, ((LocalDateTime) rows.getFirst()[1]).toInstant(ZoneOffset.UTC), 0), null);
        }, code -> new Out<LockedVoyage>(null, code)));
    }

    private static <T> T unwrap(Out<T> out) {
        if (out.refusal != null) throw new AdminException(out.refusal);
        return out.value;
    }
    @Override public String toString() { return "VoyageAssistanceService[REDACTED]"; }
}
