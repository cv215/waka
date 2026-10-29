package com.waka.referrals;

import com.waka.identity.session.PersistentSessionService;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * R05 / A34: each account gets one shareable code; an account may attach at most one referrer, once,
 * on one level, never itself. Neither the code nor the answers reveal who owns a code. A linkage
 * creates no bonus. Correction after attachment is an assistance matter, not a self-service change.
 * The deadline for attaching a referrer is not adopted yet (K22): construct explicitly; not assembled by default.
 */
public final class PersistentReferralService {
    /** No 0/O or 1/I, so a code read aloud or copied by hand stays unambiguous. */
    public static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    public static final Pattern CODE = Pattern.compile("[" + ALPHABET + "]{8}");
    public record ReferralView(String referralCode, boolean referrerAttached, Instant attachedAt) { }
    public record LinkageView(Instant attachedAt, boolean created) { }
    private record Outcome<T>(T value, ReferralException.Code refusal) { }

    private final PersistentSessionService sessions;
    private final JdbcTemplate jdbc;
    private final SecureRandom random;
    private final String gate;

    public PersistentReferralService(PersistentSessionService sessions, JdbcTemplate jdbc, SecureRandom random, Clock clock) {
        this.sessions = Objects.requireNonNull(sessions);
        this.jdbc = Objects.requireNonNull(jdbc);
        this.random = Objects.requireNonNull(random);
        if (!sessions.usesDataSource(jdbc.getDataSource()) || !sessions.usesClock(Objects.requireNonNull(clock))) {
            throw new IllegalArgumentException("REFERRAL_DEPENDENCIES_MISMATCH");
        }
        gate = sessions.identityGateHash();
    }

    /** The account's own code (assigned on first reading) and whether a referrer is attached. */
    public ReferralView mine(char[] token, String client) {
        return unwrap(sessions.withAuthenticatedSession(token, client, session -> {
            String actor = session.accountId().toString();
            String code = ownCode(actor, session.serverTime().truncatedTo(ChronoUnit.MICROS));
            if (code == null) return new Outcome<ReferralView>(null, ReferralException.Code.SERVICE_UNAVAILABLE);
            var link = jdbc.query("SELECT linked_at FROM referral_link WHERE gate_hash=? AND account_id=?",
                    (rs, n) -> instant(rs.getObject(1, LocalDateTime.class)), gate, actor);
            return new Outcome<>(new ReferralView(code, !link.isEmpty(), link.isEmpty() ? null : link.getFirst()), null);
        }));
    }

    /**
     * Attaches the owner of {@code referralCode} as referrer. The same code again finds the same
     * attachment (200); another code once attached is refused. Unknown codes and inactive owners get
     * the same answer, so a code's existence is not confirmed to strangers.
     */
    public LinkageView attach(char[] token, String client, String referralCode) {
        if (referralCode == null || !CODE.matcher(referralCode).matches()) throw new ReferralException(ReferralException.Code.INVALID_REQUEST);
        return unwrap(sessions.withAuthenticatedSession(token, client, session -> {
            String actor = session.accountId().toString();
            var owners = jdbc.query("SELECT c.account_id FROM referral_code c JOIN identity_account a ON a.gate_hash=c.gate_hash AND a.id=c.account_id "
                    + "WHERE c.gate_hash=? AND c.code=? AND a.status='ACTIVE' FOR UPDATE", (rs, n) -> rs.getString(1), gate, referralCode);
            var existing = jdbc.query("SELECT referrer_account_id,linked_at FROM referral_link WHERE gate_hash=? AND account_id=? FOR UPDATE",
                    (rs, n) -> new Object[] { rs.getString(1), instant(rs.getObject(2, LocalDateTime.class)) }, gate, actor);
            if (owners.isEmpty()) return new Outcome<LinkageView>(null, ReferralException.Code.CODE_NOT_RECOGNIZED);
            String referrer = owners.getFirst();
            if (referrer.equals(actor)) return new Outcome<LinkageView>(null, ReferralException.Code.SELF_REFERRAL);
            if (!existing.isEmpty()) {
                return referrer.equals(existing.getFirst()[0]) ? new Outcome<>(new LinkageView((Instant) existing.getFirst()[1], false), null)
                        : new Outcome<LinkageView>(null, ReferralException.Code.REFERRER_ALREADY_SET);
            }
            Instant now = session.serverTime().truncatedTo(ChronoUnit.MICROS);
            jdbc.update("INSERT INTO referral_link (gate_hash,account_id,referrer_account_id,linked_at,source) VALUES (?,?,?,?,'self_service')",
                    gate, actor, referrer, sql(now));
            return new Outcome<>(new LinkageView(now, true), null);
        }));
    }

    /** Assigns a fresh random code once; a collision simply draws again. */
    private String ownCode(String actor, Instant now) {
        var rows = jdbc.query("SELECT code FROM referral_code WHERE gate_hash=? AND account_id=? FOR UPDATE", (rs, n) -> rs.getString(1), gate, actor);
        if (!rows.isEmpty()) return rows.getFirst();
        for (int attempt = 0; attempt < 5; attempt++) {
            String candidate = draw();
            Integer taken = jdbc.queryForObject("SELECT COUNT(*) FROM referral_code WHERE gate_hash=? AND code=?", Integer.class, gate, candidate);
            if (taken != null && taken == 0) {
                jdbc.update("INSERT INTO referral_code (gate_hash,account_id,code,created_at) VALUES (?,?,?,?)", gate, actor, candidate, sql(now));
                return candidate;
            }
        }
        return null;
    }
    String draw() {
        var code = new StringBuilder(8);
        for (int i = 0; i < 8; i++) code.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        return code.toString();
    }
    private static <T> T unwrap(Outcome<T> outcome) {
        if (outcome.refusal != null) throw new ReferralException(outcome.refusal);
        return outcome.value;
    }
    private static LocalDateTime sql(Instant value) { return LocalDateTime.ofInstant(value, ZoneOffset.UTC); }
    private static Instant instant(LocalDateTime value) { return value.toInstant(ZoneOffset.UTC); }
    @Override public String toString() { return "PersistentReferralService[REDACTED]"; }
}
