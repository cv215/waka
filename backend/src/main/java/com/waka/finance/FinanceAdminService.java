package com.waka.finance;

import com.waka.identity.admin.AdminException;
import com.waka.identity.admin.AdminFunction;
import com.waka.identity.admin.PersistentAdminService;
import com.waka.identity.session.PersistentSessionService;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * FINANCE (R14): the totals of the ledger, the money WAKA should find at the provider, and the cases a person
 * must settle after checking the provider's dashboard: a payment whose amount did not match, a payment or
 * a withdrawal without a final status after an hour. Each decision is written to the audit journal; an agent
 * never settles their own money. No identity or phone number is shown.
 */
public final class FinanceAdminService {
    public record Overview(long creditXaf, long earningsXaf, long heldXaf, long payoutsInFlightXaf, long revenueXaf,
            long expectedAtProviderXaf, int openCases, Instant at) { }
    public record Case(String kind, UUID id, String state, String method, long amountXaf, Instant createdAt) { }
    private record Out<T>(T value, AdminException.Code refusal) { }
    private static final String REASON = "[A-Z][A-Z0-9_]{2,47}";

    private final PersistentAdminService admin;
    private final JdbcTemplate jdbc;
    private final String gate;

    public FinanceAdminService(PersistentSessionService sessions, PersistentAdminService admin, JdbcTemplate jdbc) {
        this.admin = Objects.requireNonNull(admin);
        this.jdbc = Objects.requireNonNull(jdbc);
        if (!Objects.requireNonNull(sessions).usesDataSource(jdbc.getDataSource())) throw new IllegalArgumentException("FINANCE_MISMATCH");
        gate = sessions.identityGateHash();
    }

    public Overview overview(char[] token, String client, char[] adminToken) {
        return unwrap(admin.withAdmin(token, client, adminToken, AdminFunction.FINANCE, (session, context) -> new Out<>(new Overview(
                total("acct:%"), total("earn:%"), total("hold:%"), total("payout:%"), Ledger.balance(jdbc, gate, Ledger.REVENUE),
                -total("provider:%"), cases(session.serverTime()).size(), session.serverTime()), null),
                code -> new Out<Overview>(null, code)));
    }

    public List<Case> cases(char[] token, String client, char[] adminToken) {
        return unwrap(admin.withAdmin(token, client, adminToken, AdminFunction.FINANCE,
                (session, context) -> new Out<>(cases(session.serverTime()), null), code -> new Out<List<Case>>(null, code)));
    }

    /**
     * After checking the provider's dashboard: {@code received} credits what really arrived (1 F up to the
     * amount asked) to the payer's WAKA credit, never to the service; {@code not_received} closes it as failed.
     */
    public Case resolvePayment(char[] token, String client, char[] adminToken, UUID paymentId, String outcome, long receivedXaf, String reasonCode) {
        if (paymentId == null || reasonCode == null || !reasonCode.matches(REASON) || !Set.of("received", "not_received").contains(outcome)
                || ("received".equals(outcome) ? receivedXaf < 1 : receivedXaf != 0)) {
            throw new AdminException(AdminException.Code.INVALID_REQUEST);
        }
        return unwrap(admin.withAdmin(token, client, adminToken, AdminFunction.FINANCE, (session, context) -> {
            var rows = jdbc.query("SELECT account_id,state,charged_xaf,created_at,provider FROM payment WHERE gate_hash=? AND id=? FOR UPDATE",
                    (rs, n) -> new Object[] { rs.getString(1), rs.getString(2), rs.getLong(3), rs.getObject(4, LocalDateTime.class), rs.getString(5) },
                    gate, paymentId.toString());
            if (rows.isEmpty() || rows.getFirst()[0].equals(context.accountId().toString())) return new Out<Case>(null, AdminException.Code.NOT_FOUND);
            Object[] p = rows.getFirst();
            Instant now = session.serverTime().truncatedTo(ChronoUnit.MICROS);
            if (!open("payment", (String) p[1], (LocalDateTime) p[3], now)) return new Out<Case>(null, AdminException.Code.NOT_FOUND);
            if ("received".equals(outcome)) {
                if (receivedXaf > (Long) p[2] || p[4] == null) return new Out<Case>(null, AdminException.Code.INVALID_REQUEST);
                Ledger.post(jdbc, gate, "payment:" + paymentId, now, List.of(
                        new Ledger.Line("provider:" + p[4], Ledger.account((String) p[0]), receivedXaf)));
            }
            jdbc.update("UPDATE payment SET state=?,applied=0,settled_at=? WHERE gate_hash=? AND id=?",
                    "received".equals(outcome) ? "succeeded" : "failed", sql(now), gate, paymentId.toString());
            audit(now, context, "PAYMENT_RESOLVED", "payment", paymentId, reasonCode, outcome.toUpperCase());
            return new Out<>(payment(paymentId.toString()), null);
        }, code -> new Out<Case>(null, code)));
    }

    /** After checking the provider's dashboard: {@code sent} closes the withdrawal; {@code not_sent} gives everything back. */
    public Case resolveWithdrawal(char[] token, String client, char[] adminToken, UUID withdrawalId, String outcome, String reasonCode) {
        if (withdrawalId == null || reasonCode == null || !reasonCode.matches(REASON) || !Set.of("sent", "not_sent").contains(outcome)) {
            throw new AdminException(AdminException.Code.INVALID_REQUEST);
        }
        return unwrap(admin.withAdmin(token, client, adminToken, AdminFunction.FINANCE, (session, context) -> {
            var rows = jdbc.query("SELECT account_id,state,amount_xaf,fee_xaf,pocket,provider,created_at FROM withdrawal WHERE gate_hash=? AND id=? FOR UPDATE",
                    (rs, n) -> new Object[] { rs.getString(1), rs.getString(2), rs.getLong(3), rs.getLong(4), rs.getString(5), rs.getString(6),
                            rs.getObject(7, LocalDateTime.class) }, gate, withdrawalId.toString());
            if (rows.isEmpty() || rows.getFirst()[0].equals(context.accountId().toString())) return new Out<Case>(null, AdminException.Code.NOT_FOUND);
            Object[] w = rows.getFirst();
            Instant now = session.serverTime().truncatedTo(ChronoUnit.MICROS);
            if (!open("withdrawal", (String) w[1], (LocalDateTime) w[6], now)) return new Out<Case>(null, AdminException.Code.NOT_FOUND);
            String id = withdrawalId.toString(), payout = "payout:" + id;
            String pocket = "earnings".equals(w[4]) ? Ledger.earnings((String) w[0]) : Ledger.account((String) w[0]);
            if ("sent".equals(outcome)) {
                Ledger.post(jdbc, gate, payout, now, List.of(new Ledger.Line(payout, "provider:" + w[5], (Long) w[2])));
            } else {
                Ledger.post(jdbc, gate, "reversal:" + id, now, List.of(new Ledger.Line(payout, pocket, (Long) w[2]),
                        new Ledger.Line(Ledger.REVENUE, pocket, (Long) w[3])));
            }
            jdbc.update("UPDATE withdrawal SET state=?,settled_at=? WHERE gate_hash=? AND id=?",
                    "sent".equals(outcome) ? "succeeded" : "failed", sql(now), gate, id);
            audit(now, context, "WITHDRAWAL_RESOLVED", "withdrawal", withdrawalId, reasonCode, outcome.toUpperCase());
            return new Out<>(withdrawal(id), null);
        }, code -> new Out<Case>(null, code)));
    }

    // ---- Helpers ----

    /** A case needs a person: an amount mismatch at once, anything else without a final status after an hour. */
    private static boolean open(String kind, String state, LocalDateTime created, Instant now) {
        if ("review".equals(state)) return true;
        boolean waiting = "payment".equals(kind) ? "pending".equals(state) : Set.of("pending", "sent").contains(state);
        return waiting && !created.toInstant(ZoneOffset.UTC).isAfter(now.minusSeconds(3600));
    }

    private List<Case> cases(Instant now) {
        LocalDateTime hourAgo = sql(now.minusSeconds(3600));
        List<Case> cases = new ArrayList<>(jdbc.query("SELECT * FROM payment WHERE gate_hash=? AND (state='review' OR (state='pending' AND created_at<=?)) "
                + "ORDER BY created_at LIMIT 100", (rs, n) -> paymentCase(rs), gate, hourAgo));
        cases.addAll(jdbc.query("SELECT * FROM withdrawal WHERE gate_hash=? AND state IN ('pending','sent') AND created_at<=? ORDER BY created_at LIMIT 100",
                (rs, n) -> withdrawalCase(rs), gate, hourAgo));
        return List.copyOf(cases);
    }
    private Case payment(String id) { return jdbc.query("SELECT * FROM payment WHERE gate_hash=? AND id=?", (rs, n) -> paymentCase(rs), gate, id).getFirst(); }
    private Case withdrawal(String id) { return jdbc.query("SELECT * FROM withdrawal WHERE gate_hash=? AND id=?", (rs, n) -> withdrawalCase(rs), gate, id).getFirst(); }
    private static Case paymentCase(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Case("payment", UUID.fromString(rs.getString("id")), rs.getString("state"), rs.getString("method"), rs.getLong("charged_xaf"),
                rs.getObject("created_at", LocalDateTime.class).toInstant(ZoneOffset.UTC));
    }
    private static Case withdrawalCase(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Case("withdrawal", UUID.fromString(rs.getString("id")), rs.getString("state"), rs.getString("method"), rs.getLong("amount_xaf"),
                rs.getObject("created_at", LocalDateTime.class).toInstant(ZoneOffset.UTC));
    }
    /** Received minus given, over every account whose name starts the same way. */
    private long total(String pattern) {
        Long in = jdbc.queryForObject("SELECT COALESCE(SUM(amount_xaf),0) FROM ledger_entry WHERE gate_hash=? AND credit_account LIKE ?", Long.class, gate, pattern);
        Long out = jdbc.queryForObject("SELECT COALESCE(SUM(amount_xaf),0) FROM ledger_entry WHERE gate_hash=? AND debit_account LIKE ?", Long.class, gate, pattern);
        return in - out;
    }
    private void audit(Instant now, PersistentAdminService.AdminContext context, String event, String objectType, UUID id, String reason, String outcome) {
        jdbc.update("INSERT INTO audit_event (occurred_at,event_type,actor_ref,actor_scope,object_type,object_ref,reason_code,outcome,correlation_ref) VALUES (?,?,?,?,?,?,?,?,?)",
                sql(now), event, context.accountId().toString(), "FINANCE", objectType, id.toString(), reason, outcome, context.adminSessionId().toString());
    }
    private static LocalDateTime sql(Instant value) { return LocalDateTime.ofInstant(value, ZoneOffset.UTC); }
    private static <T> T unwrap(Out<T> out) {
        if (out.refusal != null) throw new AdminException(out.refusal);
        return out.value;
    }
    @Override public String toString() { return "FinanceAdminService[REDACTED]"; }
}
