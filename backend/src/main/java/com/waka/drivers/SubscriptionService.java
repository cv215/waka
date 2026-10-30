package com.waka.drivers;

import com.waka.identity.admission.PersistentDriverApplicationService;
import com.waka.identity.profile.ProfileCrypto;
import com.waka.identity.session.PersistentSessionService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * R06/R12 for private drivers: one trial of seven 24-hour periods, started by the first completed paid
 * service as a driver; paid periods of thirty 24-hour periods at 3 000 XAF, bought only once the trial has
 * started, starting at payment confirmation or after the trial or paid period still running. Payment is
 * not wired yet: a purchase waits for it. Not assembled by default.
 */
public final class SubscriptionService {
    public static final Duration TRIAL = Duration.ofHours(7 * 24), PERIOD = Duration.ofHours(30 * 24);
    public static final int PRICE_XAF = 3000;
    public record PeriodView(UUID periodId, String state, int priceXaf, Instant startsAt, Instant endsAt, Instant createdAt) { }

    private final PersistentSessionService sessions;
    private final JdbcTemplate jdbc;
    private final PersistentDriverApplicationService applications;
    private final ProfileCrypto crypto;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final String gate;

    public SubscriptionService(PersistentSessionService sessions, JdbcTemplate jdbc, DataSourceTransactionManager manager,
            PersistentDriverApplicationService applications, ProfileCrypto crypto, Clock clock) {
        this.sessions = Objects.requireNonNull(sessions);
        this.jdbc = Objects.requireNonNull(jdbc);
        this.applications = Objects.requireNonNull(applications);
        this.crypto = Objects.requireNonNull(crypto);
        this.transactions = new TransactionTemplate(Objects.requireNonNull(manager));
        this.clock = Objects.requireNonNull(clock);
        if (!sessions.usesDataSource(jdbc.getDataSource()) || !sessions.usesClock(clock)) throw new IllegalArgumentException("SUBSCRIPTION_MISMATCH");
        gate = sessions.identityGateHash();
    }

    /**
     * Called inside the transaction that completes a paid service (end of a city ride, boarding of a
     * voyage): starts the trial of a private driver once, never again (a new phone or car does not restart it).
     */
    public static void startTrialIfFirst(JdbcTemplate jdbc, String gate, String driver, Instant now, String sourceType, String sourceId) {
        var category = jdbc.query("SELECT requested_category FROM driver_application WHERE gate_hash=? AND account_id=? AND state='approved'",
                (rs, n) -> rs.getString(1), gate, driver);
        if (category.isEmpty() || !"private".equals(category.getFirst())) return;
        jdbc.update("INSERT IGNORE INTO driver_trial (gate_hash,account_id,started_at,ends_at,source_type,source_id) VALUES (?,?,?,?,?,?)",
                gate, driver, sql(now), sql(now.plus(TRIAL)), sourceType, sourceId);
    }

    /** A33: a private driver whose trial has started buys a period; it waits for payment. Same key, same purchase. */
    public PeriodView purchase(char[] token, String client, UUID key) {
        if (key == null) throw new SubscriptionException(SubscriptionException.Code.INVALID_REQUEST);
        return unwrap(sessions.withAuthenticatedSession(token, client, session -> {
            String actor = session.accountId().toString();
            String request = crypto.requestMac(actor, "subscription-purchase-v1", key.toString());
            var previous = jdbc.query("SELECT id FROM subscription_period WHERE gate_hash=? AND request_mac=?", (rs, n) -> rs.getString(1), gate, request);
            if (!previous.isEmpty()) return new Object[] { period(previous.getFirst()), null };
            Instant now = session.serverTime().truncatedTo(ChronoUnit.MICROS);
            var basis = applications.accessBasisLocked(session.accountId(), now);
            if (basis == null || !"approved".equals(basis.state()) || !"private".equals(basis.category())) {
                return new Object[] { null, SubscriptionException.Code.NOT_PRIVATE_DRIVER };
            }
            // R12: no purchase before the first service; the trial must be able to start first.
            if (basis.trialEndsAt() == null) return new Object[] { null, SubscriptionException.Code.TRIAL_NOT_STARTED };
            String id = UUID.randomUUID().toString();
            jdbc.update("INSERT INTO subscription_period (gate_hash,id,account_id,state,price_xaf,request_mac,created_at) VALUES (?,?,?,'awaiting_payment',?,?,?)",
                    gate, id, actor, PRICE_XAF, request, sql(now));
            return new Object[] { period(id), null };
        }));
    }

    public List<PeriodView> mine(char[] token, String client) {
        return sessions.withAuthenticatedSession(token, client, session -> jdbc.query(
                "SELECT id FROM subscription_period WHERE gate_hash=? AND account_id=? ORDER BY created_at DESC,id LIMIT 50",
                (rs, n) -> rs.getString(1), gate, session.accountId().toString()).stream().map(this::period).toList());
    }

    /**
     * For the payment confirmation (to be wired with the provider): the period starts at the latest of now,
     * the trial end and the end of paid periods already scheduled, and lasts thirty 24-hour periods. Repeating it
     * changes nothing.
     */
    public PeriodView markPaid(UUID periodId) {
        return transactions.execute(status -> {
            var rows = jdbc.query("SELECT state FROM subscription_period WHERE gate_hash=? AND id=? FOR UPDATE",
                    (rs, n) -> rs.getString(1), gate, periodId.toString());
            if (rows.isEmpty()) throw new SubscriptionException(SubscriptionException.Code.NOT_FOUND);
            markPaidLocked(jdbc, gate, periodId.toString(), clock.instant().truncatedTo(ChronoUnit.MICROS));
            return period(periodId.toString());
        });
    }

    /** The same, inside the payment's transaction, with the period row already locked by the caller. */
    public static void markPaidLocked(JdbcTemplate jdbc, String gate, String periodId, Instant now) {
        var rows = jdbc.query("SELECT account_id,state FROM subscription_period WHERE gate_hash=? AND id=?",
                (rs, n) -> new String[] { rs.getString(1), rs.getString(2) }, gate, periodId);
        if (rows.isEmpty() || !"awaiting_payment".equals(rows.getFirst()[1])) return;
        String account = rows.getFirst()[0];
        Instant start = now;
        for (String sql : List.of("SELECT ends_at FROM driver_trial WHERE gate_hash=? AND account_id=?",
                "SELECT MAX(ends_at) FROM subscription_period WHERE gate_hash=? AND account_id=? AND state='paid'")) {
            for (var end : jdbc.query(sql, (rs, n) -> rs.getObject(1, LocalDateTime.class), gate, account)) {
                if (end != null && end.toInstant(ZoneOffset.UTC).isAfter(start)) start = end.toInstant(ZoneOffset.UTC);
            }
        }
        jdbc.update("UPDATE subscription_period SET state='paid',starts_at=?,ends_at=? WHERE gate_hash=? AND id=?",
                sql(start), sql(start.plus(PERIOD)), gate, periodId);
    }

    private PeriodView period(String id) {
        return jdbc.query("SELECT * FROM subscription_period WHERE gate_hash=? AND id=?", (rs, n) -> new PeriodView(UUID.fromString(id),
                rs.getString("state"), rs.getInt("price_xaf"), nullable(rs.getObject("starts_at", LocalDateTime.class)),
                nullable(rs.getObject("ends_at", LocalDateTime.class)), rs.getObject("created_at", LocalDateTime.class).toInstant(ZoneOffset.UTC)),
                gate, id).getFirst();
    }
    private static PeriodView unwrap(Object[] out) {
        if (out[1] != null) throw new SubscriptionException((SubscriptionException.Code) out[1]);
        return (PeriodView) out[0];
    }
    private static Instant nullable(LocalDateTime value) { return value == null ? null : value.toInstant(ZoneOffset.UTC); }
    private static LocalDateTime sql(Instant value) { return LocalDateTime.ofInstant(value, ZoneOffset.UTC); }
    @Override public String toString() { return "SubscriptionService[REDACTED]"; }
}
