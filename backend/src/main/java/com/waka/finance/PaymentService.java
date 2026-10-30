package com.waka.finance;

import com.waka.drivers.SubscriptionService;
import com.waka.identity.application.CameroonMobilePhoneFormat;
import com.waka.identity.profile.ProfileCrypto;
import com.waka.identity.session.PersistentSessionService;
import java.nio.charset.StandardCharsets;
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
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * R14 money. A client pays a ride, a voyage seat or a subscription period by Mobile Money, with their WAKA
 * credit or with their earnings, or tops the credit up. The money of a service waits in its hold until the
 * service settles it (driver and WAKA at the end or at boarding, the client on a refund). Each person has two
 * pockets: the credit (top-ups, refunds) and the earnings (driver shares, sponsor bonuses); both can be spent
 * or withdrawn to the person's own verified number. A payment is
 * confirmed only by the status read back from the provider, never by a phone or a notification body; a
 * payment that arrives too late for its service stays as credit (R07). Not assembled by default.
 */
public final class PaymentService {
    public static final int TOPUP_MIN = 500, TOPUP_MAX = 200_000, CREDIT_MAX = 300_000;
    public static final int WITHDRAW_MIN = 500, WITHDRAW_MAX = 200_000;
    /**
     * Founder's rule (30 October 2026): WAKA bears the provider's collection fee, so a client never sees it in
     * their credit; the fee is charged at withdrawal: 2 % on earnings (drivers, sponsors), 3 % on the credit.
     */
    public static final int CREDIT_WITHDRAW_FEE_PERCENT = 3, EARNINGS_WITHDRAW_FEE_PERCENT = 2;
    /** Expected provider cost (Fapshi public price: 3 % on collections, 0 % on payouts), kept for reconciliation. */
    public static final int PROVIDER_COLLECT_PERCENT = 3;
    private static final Set<String> PURPOSES = Set.of("ride", "voyage", "subscription", "topup");
    private static final Set<String> CHANNELS = Set.of("cm.mtn", "cm.orange"), POCKETS = Set.of("credit", "earnings");

    public record PaymentView(UUID paymentId, String purpose, UUID targetId, String method, int chargedXaf, int creditedXaf,
            int feeXaf, String state, boolean applied, Instant createdAt, Instant settledAt, boolean idempotentReplay) { }
    public record WithdrawalView(UUID withdrawalId, String pocket, String method, int amountXaf, int feeXaf, String state, Instant createdAt,
            Instant settledAt, boolean idempotentReplay) { }
    public record WalletLine(Instant at, String pocket, long amountXaf, String kind) { }
    public record WalletView(long creditXaf, long earningsXaf, List<WalletLine> lines) { }

    private final PersistentSessionService sessions;
    private final JdbcTemplate jdbc;
    private final PaymentProvider provider;
    private final ProfileCrypto crypto;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final String gate;

    public PaymentService(PersistentSessionService sessions, JdbcTemplate jdbc, DataSourceTransactionManager manager,
            PaymentProvider provider, ProfileCrypto crypto, Clock clock) {
        this.sessions = Objects.requireNonNull(sessions);
        this.jdbc = Objects.requireNonNull(jdbc);
        this.provider = Objects.requireNonNull(provider);
        this.crypto = Objects.requireNonNull(crypto);
        this.transactions = new TransactionTemplate(Objects.requireNonNull(manager));
        this.clock = Objects.requireNonNull(clock);
        if (!sessions.usesDataSource(jdbc.getDataSource()) || !sessions.usesClock(clock)) throw new IllegalArgumentException("PAYMENT_MISMATCH");
        gate = sessions.identityGateHash();
    }

    // ---- Paying ----

    /**
     * Pays {@code purpose} (ride, voyage, subscription: {@code target} required; topup: {@code amountXaf}
     * required) with {@code method} cm.mtn, cm.orange, credit or earnings. The amount of a service is the server's.
     * Same key, same payment.
     */
    public PaymentView pay(char[] token, String client, UUID key, String purpose, UUID target, String method, String phone, Integer amountXaf) {
        if (key == null || !PURPOSES.contains(purpose) || method == null || !(CHANNELS.contains(method) || POCKETS.contains(method))) throw invalid();
        boolean topup = "topup".equals(purpose), internal = POCKETS.contains(method);
        if (topup == (target != null) || topup != (amountXaf != null) || (topup && internal)) throw invalid();
        if (topup && (amountXaf < TOPUP_MIN || amountXaf > TOPUP_MAX)) throw invalid();
        String normalized = null;
        if (!internal) normalized = phone(phone);
        else if (phone != null) throw invalid();
        String number = normalized;
        Object[] first = sessions.withAuthenticatedSession(token, client, session -> {
            String actor = session.accountId().toString();
            String request = crypto.requestMac(actor, "payment-v1", key.toString());
            var previous = jdbc.query("SELECT id FROM payment WHERE gate_hash=? AND request_mac=?", (rs, n) -> rs.getString(1), gate, request);
            if (!previous.isEmpty()) return new Object[] { view(previous.getFirst(), true), null };
            Instant now = micros(session.serverTime());
            lockAccount(actor);
            long credited;
            int fee = 0;
            if (topup) {
                credited = amountXaf;
                // Deposits still waiting for the provider count too, so several at once cannot pass the cap.
                Long waiting = jdbc.queryForObject("SELECT COALESCE(SUM(credited_xaf),0) FROM payment WHERE gate_hash=? AND account_id=? "
                        + "AND purpose='topup' AND state='pending'", Long.class, gate, actor);
                if (Ledger.balance(jdbc, gate, Ledger.account(actor)) + waiting + credited > CREDIT_MAX) {
                    return new Object[] { null, PaymentException.Code.CREDIT_LIMIT };
                }
            } else {
                Long price = payable(purpose, target.toString(), actor, now);
                if (price == null) return new Object[] { null, PaymentException.Code.NOT_PAYABLE };
                credited = price;
            }
            String id = UUID.randomUUID().toString();
            long charged = credited + fee;
            if (internal) {
                String pocket = pocket(method, actor);
                if (Ledger.balance(jdbc, gate, pocket) < credited) return new Object[] { null, PaymentException.Code.INSUFFICIENT_CREDIT };
                insert(id, actor, purpose, target, method, charged, credited, fee, 0, "succeeded", null, request, now);
                apply(id, purpose, target.toString(), pocket, credited, now);
                jdbc.update("UPDATE payment SET applied=1,settled_at=? WHERE gate_hash=? AND id=?", sql(now), gate, id);
                return new Object[] { view(id, false), null };
            }
            insert(id, actor, purpose, target, method, charged, credited, fee, percentUp(charged, PROVIDER_COLLECT_PERCENT), "pending", number, request, now);
            return new Object[] { id, null };
        });
        if (first[1] != null) throw new PaymentException((PaymentException.Code) first[1]);
        if (first[0] instanceof PaymentView done) return done;
        // Phase 2, outside any transaction: ask the provider to push the confirmation to the payer's phone.
        String id = (String) first[0];
        var row = row(id);
        PaymentProvider.Started started;
        try {
            started = provider.collect(id, row.charged, method, number, description(purpose));
        } catch (PaymentProvider.ProviderUnavailable unknown) {
            // Nothing is known: the payment stays pending and is looked up again by its own reference.
            started = new PaymentProvider.Started(id);
        }
        String reference = started.reference();
        transactions.executeWithoutResult(status -> {
            if (reference == null) {
                jdbc.update("UPDATE payment SET state='failed',settled_at=? WHERE gate_hash=? AND id=? AND state='pending'", sql(now()), gate, id);
            } else {
                jdbc.update("UPDATE payment SET provider=?,provider_reference=? WHERE gate_hash=? AND id=?", provider.name(), reference, gate, id);
            }
        });
        return view(id, false);
    }

    /** The payer reads their payment; a pending one is looked up again at the provider first. */
    public PaymentView read(char[] token, String client, UUID paymentId) {
        if (paymentId == null) throw invalid();
        String owner = sessions.withAuthenticatedSession(token, client, session -> {
            var rows = jdbc.query("SELECT account_id FROM payment WHERE gate_hash=? AND id=?", (rs, n) -> rs.getString(1), gate, paymentId.toString());
            return rows.isEmpty() || !rows.getFirst().equals(session.accountId().toString()) ? null : rows.getFirst();
        });
        if (owner == null) throw new PaymentException(PaymentException.Code.NOT_FOUND);
        refreshPayment(paymentId.toString());
        return view(paymentId.toString(), false);
    }

    /** Looks the payment up at the provider and settles it once. Safe to repeat. */
    public void refreshPayment(String id) {
        Row r = row(id);
        if (r == null || !"pending".equals(r.state) || r.reference == null) return;
        PaymentProvider.Status status;
        try { status = provider.payment(r.reference); }
        catch (PaymentProvider.ProviderUnavailable unknown) { return; }
        if (status.state() == PaymentProvider.State.PENDING) return;
        transactions.executeWithoutResult(tx -> {
            lockAccount(r.account);
            Row locked = jdbc.query("SELECT * FROM payment WHERE gate_hash=? AND id=? FOR UPDATE", this::map, gate, id).getFirst();
            if (!"pending".equals(locked.state)) return;
            Instant now = now();
            if (status.state() == PaymentProvider.State.FAILED) {
                jdbc.update("UPDATE payment SET state='failed',settled_at=? WHERE gate_hash=? AND id=?", sql(now), gate, id);
                return;
            }
            // R07: operator, reference, currency, amount and status must all be established by the provider.
            if (status.amountXaf() == null || status.amountXaf() != locked.charged || !"XAF".equalsIgnoreCase(status.currency())) {
                jdbc.update("UPDATE payment SET state='review',settled_at=? WHERE gate_hash=? AND id=?", sql(now), gate, id);
                return;
            }
            String from = "provider:" + provider.name();
            Ledger.post(jdbc, gate, "payment:" + id, now, List.of(
                    new Ledger.Line(from, Ledger.account(locked.account), locked.credited),
                    new Ledger.Line(from, Ledger.REVENUE, locked.fee)));
            boolean applied = false;
            if (!"topup".equals(locked.purpose) && payable(locked.purpose, locked.target, locked.account, now) != null) {
                apply(id, locked.purpose, locked.target, Ledger.account(locked.account), locked.credited, now);
                applied = true;
            }
            jdbc.update("UPDATE payment SET state='succeeded',applied=?,settled_at=? WHERE gate_hash=? AND id=?", applied ? 1 : 0, sql(now), gate, id);
        });
    }

    // ---- Credit and withdrawals ----

    public WalletView wallet(char[] token, String client) {
        return sessions.withAuthenticatedSession(token, client, session -> {
            String credit = Ledger.account(session.accountId().toString()), earnings = Ledger.earnings(session.accountId().toString());
            var lines = jdbc.query("SELECT journal,debit_account,credit_account,amount_xaf,created_at FROM ledger_entry "
                    + "WHERE gate_hash=? AND (debit_account IN (?,?) OR credit_account IN (?,?)) ORDER BY id DESC LIMIT 50", (rs, n) -> {
                boolean in = credit.equals(rs.getString("credit_account")) || earnings.equals(rs.getString("credit_account"));
                String mine = in ? rs.getString("credit_account") : rs.getString("debit_account");
                return new WalletLine(rs.getObject("created_at", LocalDateTime.class).toInstant(ZoneOffset.UTC),
                        mine.startsWith("earn:") ? "earnings" : "credit", in ? rs.getLong("amount_xaf") : -rs.getLong("amount_xaf"),
                        kind(rs.getString("journal"), in));
            }, gate, credit, earnings, credit, earnings);
            return new WalletView(Ledger.balance(jdbc, gate, credit), Ledger.balance(jdbc, gate, earnings), lines);
        });
    }

    /**
     * Sends money from a pocket to the account's own verified number. The fee (3 % on the credit, 2 % on
     * earnings) is taken from the same pocket on top of the amount sent. Same key, same withdrawal.
     */
    public WithdrawalView withdraw(char[] token, String client, UUID key, String pocketName, int amountXaf, String method, String phone) {
        if (key == null || !POCKETS.contains(pocketName) || !CHANNELS.contains(method) || amountXaf < WITHDRAW_MIN || amountXaf > WITHDRAW_MAX) throw invalid();
        String number = phone(phone);
        Object[] first = sessions.withAuthenticatedSession(token, client, session -> {
            String actor = session.accountId().toString();
            String request = crypto.requestMac(actor, "withdrawal-v1", key.toString());
            var previous = jdbc.query("SELECT id FROM withdrawal WHERE gate_hash=? AND request_mac=?", (rs, n) -> rs.getString(1), gate, request);
            if (!previous.isEmpty()) return new Object[] { withdrawalView(previous.getFirst(), true), null };
            if (!sessions.isAccountPhone(session.accountId(), number)) return new Object[] { null, PaymentException.Code.PHONE_NOT_ACCOUNT };
            Instant now = micros(session.serverTime());
            lockAccount(actor);
            int fee = percentUp(amountXaf, "earnings".equals(pocketName) ? EARNINGS_WITHDRAW_FEE_PERCENT : CREDIT_WITHDRAW_FEE_PERCENT);
            String pocket = pocket(pocketName, actor);
            if (Ledger.balance(jdbc, gate, pocket) < (long) amountXaf + fee) return new Object[] { null, PaymentException.Code.INSUFFICIENT_CREDIT };
            String id = UUID.randomUUID().toString();
            jdbc.update("INSERT INTO withdrawal (gate_hash,id,account_id,method,pocket,amount_xaf,fee_xaf,state,provider,phone_cipher,key_id,request_mac,created_at) "
                    + "VALUES (?,?,?,?,?,?,?,'pending',?,?,?,?,?)", gate, id, actor, method, pocketName, amountXaf, fee, provider.name(),
                    crypto.encrypt(number.getBytes(StandardCharsets.US_ASCII), phoneAad("withdrawal", id)), crypto.keyId(), request, sql(now));
            Ledger.post(jdbc, gate, "withdrawal:" + id, now, List.of(
                    new Ledger.Line(pocket, "payout:" + id, amountXaf),
                    new Ledger.Line(pocket, Ledger.REVENUE, fee)));
            return new Object[] { id, null };
        });
        if (first[1] != null) throw new PaymentException((PaymentException.Code) first[1]);
        if (first[0] instanceof WithdrawalView done) return done;
        String id = (String) first[0];
        PaymentProvider.Started started;
        try {
            started = provider.send(id, amountXaf, method, number, "Retrait WAKA");
        } catch (PaymentProvider.ProviderUnavailable unknown) {
            // Unknown outcome: never refunded before the provider gives a final status.
            started = new PaymentProvider.Started(id);
        }
        String reference = started.reference();
        transactions.executeWithoutResult(tx -> {
            if (reference == null) {
                reverse(id, now());
            } else {
                jdbc.update("UPDATE withdrawal SET state='sent',provider_reference=? WHERE gate_hash=? AND id=? AND state='pending'", reference, gate, id);
            }
        });
        return withdrawalView(id, false);
    }

    public WithdrawalView readWithdrawal(char[] token, String client, UUID withdrawalId) {
        if (withdrawalId == null) throw invalid();
        boolean mine = sessions.withAuthenticatedSession(token, client, session -> !jdbc.query(
                "SELECT id FROM withdrawal WHERE gate_hash=? AND id=? AND account_id=?", (rs, n) -> rs.getString(1),
                gate, withdrawalId.toString(), session.accountId().toString()).isEmpty());
        if (!mine) throw new PaymentException(PaymentException.Code.NOT_FOUND);
        refreshWithdrawal(withdrawalId.toString());
        return withdrawalView(withdrawalId.toString(), false);
    }

    public void refreshWithdrawal(String id) {
        var rows = jdbc.query("SELECT state,provider_reference FROM withdrawal WHERE gate_hash=? AND id=?",
                (rs, n) -> new String[] { rs.getString(1), rs.getString(2) }, gate, id);
        if (rows.isEmpty() || !"sent".equals(rows.getFirst()[0])) return;
        PaymentProvider.Status status;
        try { status = provider.transfer(rows.getFirst()[1]); }
        catch (PaymentProvider.ProviderUnavailable unknown) { return; }
        if (status.state() == PaymentProvider.State.PENDING) return;
        transactions.executeWithoutResult(tx -> {
            var locked = jdbc.query("SELECT state,amount_xaf FROM withdrawal WHERE gate_hash=? AND id=? FOR UPDATE",
                    (rs, n) -> new Object[] { rs.getString(1), rs.getLong(2) }, gate, id).getFirst();
            if (!"sent".equals(locked[0])) return;
            Instant now = now();
            if (status.state() == PaymentProvider.State.FAILED) {
                reverse(id, now);
            } else {
                Ledger.post(jdbc, gate, "payout:" + id, now, List.of(new Ledger.Line("payout:" + id, "provider:" + provider.name(), (Long) locked[1])));
                jdbc.update("UPDATE withdrawal SET state='succeeded',settled_at=? WHERE gate_hash=? AND id=?", sql(now), gate, id);
            }
        });
    }

    // ---- Provider notifications ----

    /** A signed notification is only a reason to look again at the payments and withdrawals it mentions. */
    public void notification(byte[] body, java.util.function.UnaryOperator<String> header) {
        if (!provider.authentic(body, header)) throw new PaymentException(PaymentException.Code.NOT_AUTHENTIC);
        for (String reference : provider.references(body)) {
            for (String id : jdbc.query("SELECT id FROM payment WHERE gate_hash=? AND (provider_reference=? OR id=?)",
                    (rs, n) -> rs.getString(1), gate, reference, reference)) refreshPayment(id);
            for (String id : jdbc.query("SELECT id FROM withdrawal WHERE gate_hash=? AND (provider_reference=? OR id=?)",
                    (rs, n) -> rs.getString(1), gate, reference, reference)) refreshWithdrawal(id);
        }
    }

    // ---- Services ----

    /** The price still to pay for a service of this payer, or null when it cannot be paid now. Locks the row. */
    private Long payable(String purpose, String target, String payer, Instant now) {
        return switch (purpose) {
            case "ride" -> first(jdbc.query("SELECT price_xaf FROM ride_reservation WHERE gate_hash=? AND id=? AND client_account_id=? "
                    + "AND state='awaiting_payment' AND hold_expires_at>? FOR UPDATE", (rs, n) -> rs.getLong(1), gate, target, payer, sql(now)));
            case "voyage" -> first(jdbc.query("SELECT total_xaf FROM voyage_reservation WHERE gate_hash=? AND id=? AND client_account_id=? "
                    + "AND state='awaiting_payment' AND hold_expires_at>? FOR UPDATE", (rs, n) -> rs.getLong(1), gate, target, payer, sql(now)));
            case "subscription" -> first(jdbc.query("SELECT price_xaf FROM subscription_period WHERE gate_hash=? AND id=? AND account_id=? "
                    + "AND state='awaiting_payment' FOR UPDATE", (rs, n) -> rs.getLong(1), gate, target, payer));
            default -> null;
        };
    }

    /** Moves money from the payer's pocket to the service and confirms it, with the row locked by {@link #payable}. */
    private void apply(String paymentId, String purpose, String target, String fromPocket, long amount, Instant now) {
        String to = "subscription".equals(purpose) ? Ledger.REVENUE : Ledger.hold(purpose, target);
        Ledger.post(jdbc, gate, "apply:" + paymentId, now, List.of(new Ledger.Line(fromPocket, to, amount)));
        switch (purpose) {
            case "ride" -> jdbc.update("UPDATE ride_reservation SET state='confirmed' WHERE gate_hash=? AND id=? AND state='awaiting_payment'", gate, target);
            case "voyage" -> jdbc.update("UPDATE voyage_reservation SET state='confirmed' WHERE gate_hash=? AND id=? AND state='awaiting_payment'", gate, target);
            default -> SubscriptionService.markPaidLocked(jdbc, gate, target, now);
        }
    }

    // ---- Helpers ----

    private void reverse(String id, Instant now) {
        var w = jdbc.query("SELECT account_id,amount_xaf,fee_xaf,pocket FROM withdrawal WHERE gate_hash=? AND id=?",
                (rs, n) -> new Object[] { rs.getString(1), rs.getLong(2), rs.getLong(3), rs.getString(4) }, gate, id).getFirst();
        String pocket = pocket((String) w[3], (String) w[0]);
        Ledger.post(jdbc, gate, "reversal:" + id, now, List.of(
                new Ledger.Line("payout:" + id, pocket, (Long) w[1]),
                new Ledger.Line(Ledger.REVENUE, pocket, (Long) w[2])));
        jdbc.update("UPDATE withdrawal SET state='failed',settled_at=? WHERE gate_hash=? AND id=?", sql(now), gate, id);
    }

    private static String pocket(String name, String account) {
        return "earnings".equals(name) ? Ledger.earnings(account) : Ledger.account(account);
    }

    /**
     * Fapshi never repeats a lost notification: payments and withdrawals still open after a minute are looked
     * up again. To be called periodically once the service is assembled. Returns how many were looked up.
     */
    public int refreshPending(int limit) {
        var cutoff = sql(now().minusSeconds(60));
        var payments = jdbc.query("SELECT id FROM payment WHERE gate_hash=? AND state='pending' AND provider_reference IS NOT NULL "
                + "AND created_at<=? ORDER BY created_at LIMIT ?", (rs, n) -> rs.getString(1), gate, cutoff, limit);
        payments.forEach(this::refreshPayment);
        var withdrawals = jdbc.query("SELECT id FROM withdrawal WHERE gate_hash=? AND state='sent' AND created_at<=? ORDER BY created_at LIMIT ?",
                (rs, n) -> rs.getString(1), gate, cutoff, limit);
        withdrawals.forEach(this::refreshWithdrawal);
        return payments.size() + withdrawals.size();
    }

    /** Serializes the money operations of one person. */
    private void lockAccount(String account) {
        jdbc.query("SELECT id FROM identity_account WHERE gate_hash=? AND id=? FOR UPDATE", (rs, n) -> rs.getString(1), gate, account);
    }

    private void insert(String id, String actor, String purpose, UUID target, String method, long charged, long credited, int fee,
            int providerCost, String state, String phone, String request, Instant now) {
        jdbc.update("INSERT INTO payment (gate_hash,id,account_id,purpose,target_id,method,charged_xaf,credited_xaf,fee_xaf,expected_provider_cost_xaf,"
                + "state,phone_cipher,key_id,request_mac,created_at,settled_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                gate, id, actor, purpose, target == null ? null : target.toString(), method, charged, credited, fee, providerCost, state,
                phone == null ? null : crypto.encrypt(phone.getBytes(StandardCharsets.US_ASCII), phoneAad("payment", id)),
                phone == null ? null : crypto.keyId(), request, sql(now), "succeeded".equals(state) ? sql(now) : null);
    }

    private record Row(String id, String account, String purpose, String target, long charged, long credited, long fee,
            String state, boolean applied, String method, String reference, Instant createdAt, Instant settledAt) { }
    private Row map(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new Row(rs.getString("id"), rs.getString("account_id"), rs.getString("purpose"), rs.getString("target_id"),
                rs.getLong("charged_xaf"), rs.getLong("credited_xaf"), rs.getLong("fee_xaf"), rs.getString("state"), rs.getInt("applied") == 1,
                rs.getString("method"), rs.getString("provider_reference"), instant(rs.getObject("created_at", LocalDateTime.class)),
                instant(rs.getObject("settled_at", LocalDateTime.class)));
    }
    private Row row(String id) { return first(jdbc.query("SELECT * FROM payment WHERE gate_hash=? AND id=?", this::map, gate, id)); }
    private PaymentView view(String id, boolean replay) {
        Row r = row(id);
        return new PaymentView(UUID.fromString(r.id), r.purpose, r.target == null ? null : UUID.fromString(r.target), r.method,
                (int) r.charged, (int) r.credited, (int) r.fee, r.state, r.applied, r.createdAt, r.settledAt, replay);
    }
    private WithdrawalView withdrawalView(String id, boolean replay) {
        return jdbc.query("SELECT * FROM withdrawal WHERE gate_hash=? AND id=?", (rs, n) -> new WithdrawalView(UUID.fromString(id),
                rs.getString("pocket"), rs.getString("method"), rs.getInt("amount_xaf"), rs.getInt("fee_xaf"),
                // "sent" is an internal detail: for the person the withdrawal is still pending.
                "sent".equals(rs.getString("state")) ? "pending" : rs.getString("state"),
                instant(rs.getObject("created_at", LocalDateTime.class)), instant(rs.getObject("settled_at", LocalDateTime.class)), replay),
                gate, id).getFirst();
    }
    private static String kind(String journal, boolean in) {
        String prefix = journal.substring(0, journal.indexOf(':'));
        return switch (prefix) {
            case "payment" -> "deposit";
            case "apply" -> "service_payment";
            case "release" -> in ? "settlement" : "service_payment";
            case "withdrawal" -> "withdrawal";
            case "reversal" -> "withdrawal_reversed";
            default -> "other";
        };
    }
    private static String phone(String phone) {
        try { return CameroonMobilePhoneFormat.normalize(phone); }
        catch (IllegalArgumentException invalid) { throw invalid(); }
    }
    private byte[] phoneAad(String kind, String id) { return ProfileCrypto.aad("waka-" + kind + "-phone-v1", gate, id, crypto.keyId()); }
    private static String description(String purpose) {
        return switch (purpose) {
            case "ride" -> "Course WAKA";
            case "voyage" -> "Voyage WAKA";
            case "subscription" -> "Abonnement chauffeur WAKA";
            default -> "Credit WAKA";
        };
    }
    /** Ceiling of percent of amount, in whole francs. */
    static int percentUp(long amount, int percent) { return (int) ((amount * percent + 99) / 100); }
    private static <T> T first(List<T> rows) { return rows.isEmpty() ? null : rows.getFirst(); }
    private Instant now() { return micros(clock.instant()); }
    private static Instant micros(Instant value) { return value.truncatedTo(ChronoUnit.MICROS); }
    private static Instant instant(LocalDateTime value) { return value == null ? null : value.toInstant(ZoneOffset.UTC); }
    private static LocalDateTime sql(Instant value) { return LocalDateTime.ofInstant(value, ZoneOffset.UTC); }
    private static PaymentException invalid() { return new PaymentException(PaymentException.Code.INVALID_REQUEST); }
    @Override public String toString() { return "PaymentService[REDACTED]"; }
}
