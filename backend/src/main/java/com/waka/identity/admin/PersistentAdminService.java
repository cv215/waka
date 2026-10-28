package com.waka.identity.admin;

import com.waka.identity.session.PersistentSessionService;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 05.05: nominative administrative access. A client session alone never opens it: an active grant
 * and a single-use TOTP code are both required, and the administrative session dies with its
 * client session. Construct explicitly after qualifying keys and policy; not assembled by default.
 */
public final class PersistentAdminService {
    /** Shown once at enrollment; the secret is never returned again. */
    public record Enrollment(String secret, String otpauthUri) {
        @Override public String toString() { return "Enrollment[REDACTED]"; }
    }
    public record AdminContext(UUID accountId, UUID adminSessionId, List<AdminFunction> functions,
            Instant idleExpiresAt, Instant absoluteExpiresAt, Instant serverTime) {
        public boolean allows(AdminFunction function) { return functions.contains(function); }
        @Override public String toString() { return "AdminContext[REDACTED]"; }
    }
    public static final class AdminGrant implements AutoCloseable {
        private final char[] token;
        private final AdminContext context;
        AdminGrant(char[] token, AdminContext context) { this.token = token; this.context = context; }
        public synchronized char[] token() { return token.clone(); }
        public AdminContext context() { return context; }
        @Override public synchronized void close() { Arrays.fill(token, '\0'); }
        @Override public String toString() { return "AdminGrant[REDACTED]"; }
    }
    private record TotpRow(String state, String keyId, byte[] cipher, Instant createdAt, long lastStep, int failures,
            Instant windowStart, Instant lockedUntil) {
        @Override public String toString() { return "AdminTotpRow[REDACTED]"; }
    }
    private record Outcome<T>(T value, AdminException.Code refusal) { }
    public static final String FOUNDER_BOOTSTRAP = "FOUNDER_BOOTSTRAP";
    private final PersistentSessionService sessions;
    private final JdbcTemplate jdbc;
    private final AdminCrypto crypto;
    private final AdminPolicy policy;
    private final Clock clock;
    private final String gate;
    private final TransactionTemplate maintenance;

    public PersistentAdminService(PersistentSessionService sessions, JdbcTemplate jdbc, DataSourceTransactionManager manager,
            AdminCrypto crypto, AdminPolicy policy, Clock clock) {
        this.sessions = Objects.requireNonNull(sessions);
        this.jdbc = Objects.requireNonNull(jdbc);
        this.crypto = Objects.requireNonNull(crypto);
        this.policy = Objects.requireNonNull(policy);
        this.clock = Objects.requireNonNull(clock);
        if (jdbc.getDataSource() == null || jdbc.getDataSource() != Objects.requireNonNull(manager).getDataSource()
                || !sessions.usesDataSource(jdbc.getDataSource()) || !sessions.usesClock(clock))
            throw new IllegalArgumentException("ADMIN_DEPENDENCIES_MISMATCH");
        if (crypto.reusesSessionKey(sessions)) throw new IllegalArgumentException("ADMIN_SESSION_KEY_REUSE");
        gate = sessions.identityGateHash();
        maintenance = new TransactionTemplate(manager);
        maintenance.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        maintenance.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    // ---- Local maintenance only (never exposed over HTTP) ----

    /** The founder's first grants, allowed only while no grant has ever existed in this namespace. */
    public void bootstrapFounder(UUID accountId, Set<AdminFunction> functions) {
        if (accountId == null || functions == null || functions.isEmpty()) throw invalid();
        unwrap(maintenance.execute(status -> {
            lockGate();
            if (!"ACTIVE".equals(accountStatus(accountId))) return new Outcome<>(null, AdminException.Code.ACCOUNT_NOT_FOUND);
            Integer existing = jdbc.queryForObject("SELECT COUNT(*) FROM admin_grant WHERE gate_hash=?", Integer.class, gate);
            if (existing != null && existing > 0) return new Outcome<>(null, AdminException.Code.BOOTSTRAP_ALREADY_DONE);
            Instant now = now();
            for (AdminFunction function : EnumSet.copyOf(functions)) {
                jdbc.update("INSERT INTO admin_grant (gate_hash,id,account_id,admin_function,granted_at,granted_by,reason_code) VALUES (?,?,?,?,?,?,?)",
                        gate, UUID.randomUUID().toString(), accountId.toString(), function.name(), sql(now), FOUNDER_BOOTSTRAP, FOUNDER_BOOTSTRAP);
            }
            return new Outcome<>(Boolean.TRUE, null);
        }));
    }

    /** Lost phone: removes the second factor and ends open administrative sessions; grants are kept. */
    public void resetSecondFactor(UUID accountId) {
        if (accountId == null) throw invalid();
        maintenance.execute(status -> {
            lockGate();
            Instant now = now();
            jdbc.update("DELETE FROM admin_totp WHERE gate_hash=? AND account_id=?", gate, accountId.toString());
            return jdbc.update("UPDATE admin_session SET ended_at=?,end_reason='FACTOR_RESET' WHERE gate_hash=? AND account_id=? AND ended_at IS NULL",
                    sql(now), gate, accountId.toString());
        });
    }

    // ---- Authenticated client session, then second factor ----

    /** Starts (or restarts while unconfirmed) an enrollment for an account holding at least one grant. */
    public Enrollment enroll(char[] token, String client) {
        return unwrap(sessions.withAuthenticatedSession(token, client, session -> {
            String actor = session.accountId().toString();
            if (activeFunctions(actor).isEmpty()) return new Outcome<Enrollment>(null, AdminException.Code.NOT_AUTHORIZED);
            TotpRow row = totp(actor);
            if (row != null && "active".equals(row.state)) return new Outcome<Enrollment>(null, AdminException.Code.ALREADY_ENROLLED);
            Instant now = session.serverTime();
            byte[] secret = crypto.newSecret();
            try {
                byte[] cipher = crypto.encrypt(secret, secretAad(actor, now, crypto.keyId()));
                jdbc.update("DELETE FROM admin_totp WHERE gate_hash=? AND account_id=?", gate, actor);
                jdbc.update("INSERT INTO admin_totp (gate_hash,account_id,state,key_id,secret_cipher,created_at,last_step,failures) VALUES (?,?,'pending',?,?,?,-1,0)",
                        gate, actor, crypto.keyId(), cipher, sql(now));
                String encoded = Totp.base32(secret);
                // The label names the service only: no phone number or account identifier.
                String uri = "otpauth://totp/" + URLEncoder.encode("WAKA:administration", StandardCharsets.UTF_8)
                        + "?secret=" + encoded + "&issuer=WAKA&algorithm=SHA1&digits=6&period=30";
                return new Outcome<>(new Enrollment(encoded, uri), null);
            } finally { Arrays.fill(secret, (byte) 0); }
        }));
    }

    /** Proves the authenticator works; only then can an administrative session be opened. */
    public void confirmEnrollment(char[] token, String client, String code) {
        unwrap(sessions.withAuthenticatedSession(token, client, session -> {
            String actor = session.accountId().toString();
            if (activeFunctions(actor).isEmpty()) return new Outcome<>(null, AdminException.Code.NOT_AUTHORIZED);
            TotpRow row = totp(actor);
            if (row == null) return new Outcome<>(null, AdminException.Code.ENROLLMENT_REQUIRED);
            if ("active".equals(row.state)) return new Outcome<>(null, AdminException.Code.ALREADY_ENROLLED);
            Instant now = session.serverTime();
            AdminException.Code refusal = checkCode(actor, row, code, now);
            if (refusal != null) return new Outcome<>(null, refusal);
            jdbc.update("UPDATE admin_totp SET state='active',confirmed_at=? WHERE gate_hash=? AND account_id=?", sql(now), gate, actor);
            return new Outcome<>(Boolean.TRUE, null);
        }));
    }

    /** Opens an administrative session. Not replayable: a lost answer needs a new code (K15). */
    public AdminGrant open(char[] token, String client, String code) {
        return unwrap(sessions.withAuthenticatedSession(token, client, session -> {
            String actor = session.accountId().toString();
            List<AdminFunction> functions = activeFunctions(actor);
            if (functions.isEmpty()) return new Outcome<AdminGrant>(null, AdminException.Code.NOT_AUTHORIZED);
            TotpRow row = totp(actor);
            if (row == null || !"active".equals(row.state)) return new Outcome<AdminGrant>(null, AdminException.Code.ENROLLMENT_REQUIRED);
            Instant now = session.serverTime();
            AdminException.Code refusal = checkCode(actor, row, code, now);
            if (refusal != null) return new Outcome<AdminGrant>(null, refusal);
            char[] adminToken = crypto.newToken();
            UUID id = UUID.randomUUID();
            Instant absolute = earliest(now.plus(policy.absoluteLifetime()), session.absoluteExpiresAt());
            Instant idle = earliest(now.plus(policy.idleLifetime()), absolute);
            jdbc.update("INSERT INTO admin_session (gate_hash,id,account_id,base_session_id,token_hash,created_at,last_seen_at,idle_expires_at,absolute_expires_at) VALUES (?,?,?,?,?,?,?,?,?)",
                    gate, id.toString(), actor, session.sessionId().toString(), crypto.tokenHash(adminToken),
                    sql(now), sql(now), sql(idle), sql(absolute));
            return new Outcome<>(new AdminGrant(adminToken, new AdminContext(session.accountId(), id, functions, idle, absolute, now)), null);
        }));
    }

    /** Checks both proofs and the current grants; every administrative route starts here. */
    public AdminContext authenticate(char[] token, String client, char[] adminToken) {
        if (adminToken == null) throw new AdminException(AdminException.Code.INVALID_ADMIN_SESSION);
        String hash = crypto.tokenHash(adminToken);
        return unwrap(sessions.withAuthenticatedSession(token, client, session -> {
            String actor = session.accountId().toString();
            var rows = jdbc.query("SELECT id,account_id,base_session_id,token_hash,idle_expires_at,absolute_expires_at,ended_at FROM admin_session WHERE gate_hash=? AND token_hash=? FOR UPDATE",
                    (rs, n) -> new Object[] { rs.getString("id"), rs.getString("account_id"), rs.getString("base_session_id"),
                            rs.getString("token_hash"), instant(rs.getObject("idle_expires_at", LocalDateTime.class)),
                            instant(rs.getObject("absolute_expires_at", LocalDateTime.class)), rs.getObject("ended_at", LocalDateTime.class) },
                    gate, hash);
            if (rows.isEmpty()) return new Outcome<AdminContext>(null, AdminException.Code.INVALID_ADMIN_SESSION);
            Object[] row = rows.getFirst();
            String id = (String) row[0];
            Instant now = session.serverTime(), idle = (Instant) row[4], absolute = (Instant) row[5];
            if (!actor.equals(row[1]) || !session.sessionId().toString().equals(row[2])
                    || !AdminCrypto.equal(hash, (String) row[3]) || row[6] != null) {
                return new Outcome<AdminContext>(null, AdminException.Code.INVALID_ADMIN_SESSION);
            }
            if (!now.isBefore(idle) || !now.isBefore(absolute)) {
                end(id, now, "EXPIRED");
                return new Outcome<AdminContext>(null, AdminException.Code.INVALID_ADMIN_SESSION);
            }
            List<AdminFunction> functions = activeFunctions(actor);
            if (functions.isEmpty()) {
                end(id, now, "NO_GRANT");
                return new Outcome<AdminContext>(null, AdminException.Code.NOT_AUTHORIZED);
            }
            Instant nextIdle = earliest(now.plus(policy.idleLifetime()), absolute);
            jdbc.update("UPDATE admin_session SET last_seen_at=?,idle_expires_at=? WHERE gate_hash=? AND id=?", sql(now), sql(nextIdle), gate, id);
            return new Outcome<>(new AdminContext(session.accountId(), UUID.fromString(id), functions, nextIdle, absolute, now), null);
        }));
    }

    /** Explicit end of the administrative session; the client session stays open. */
    public void close(char[] token, String client, char[] adminToken) {
        if (adminToken == null) throw new AdminException(AdminException.Code.INVALID_ADMIN_SESSION);
        String hash = crypto.tokenHash(adminToken);
        unwrap(sessions.withAuthenticatedSession(token, client, session -> {
            int ended = jdbc.update("UPDATE admin_session SET ended_at=?,end_reason='LOGOUT' WHERE gate_hash=? AND token_hash=? AND account_id=? AND base_session_id=? AND ended_at IS NULL",
                    sql(session.serverTime()), gate, hash, session.accountId().toString(), session.sessionId().toString());
            return ended == 1 ? new Outcome<>(Boolean.TRUE, null) : new Outcome<Boolean>(null, AdminException.Code.INVALID_ADMIN_SESSION);
        }));
    }

    /** Single-use code with drift of one step, failure window and temporary lock; state changes are committed. */
    private AdminException.Code checkCode(String actor, TotpRow row, String code, Instant now) {
        if (row.lockedUntil != null && now.isBefore(row.lockedUntil)) return AdminException.Code.LOCKED;
        if (!crypto.keyId().equals(row.keyId)) throw new AdminException(AdminException.Code.SERVICE_UNAVAILABLE);
        byte[] secret = crypto.decrypt(row.cipher, secretAad(actor, row.createdAt, row.keyId));
        long step;
        try { step = Totp.verify(secret, code, now, row.lastStep); }
        finally { Arrays.fill(secret, (byte) 0); }
        if (step >= 0) {
            jdbc.update("UPDATE admin_totp SET last_step=?,failures=0,failure_window_start=NULL,locked_until=NULL WHERE gate_hash=? AND account_id=?",
                    step, gate, actor);
            return null;
        }
        boolean fresh = row.windowStart == null || !now.isBefore(row.windowStart.plus(policy.failureWindow()));
        int failures = fresh ? 1 : row.failures + 1;
        Instant windowStart = fresh ? now : row.windowStart;
        if (failures >= policy.maxFailures()) {
            jdbc.update("UPDATE admin_totp SET failures=0,failure_window_start=NULL,locked_until=? WHERE gate_hash=? AND account_id=?",
                    sql(now.plus(policy.lockDuration())), gate, actor);
            return AdminException.Code.LOCKED;
        }
        jdbc.update("UPDATE admin_totp SET failures=?,failure_window_start=? WHERE gate_hash=? AND account_id=?",
                failures, sql(windowStart), gate, actor);
        return AdminException.Code.INVALID_SECOND_FACTOR;
    }

    private List<AdminFunction> activeFunctions(String actor) {
        return jdbc.query("SELECT DISTINCT admin_function FROM admin_grant WHERE gate_hash=? AND account_id=? AND revoked_at IS NULL ORDER BY admin_function",
                (rs, n) -> AdminFunction.valueOf(rs.getString(1)), gate, actor).stream().sorted().toList();
    }
    private TotpRow totp(String actor) {
        var rows = jdbc.query("SELECT state,key_id,secret_cipher,created_at,last_step,failures,failure_window_start,locked_until FROM admin_totp WHERE gate_hash=? AND account_id=? FOR UPDATE",
                (rs, n) -> new TotpRow(rs.getString("state"), rs.getString("key_id"), rs.getBytes("secret_cipher"),
                        instant(rs.getObject("created_at", LocalDateTime.class)), rs.getLong("last_step"), rs.getInt("failures"),
                        nullable(rs.getObject("failure_window_start", LocalDateTime.class)),
                        nullable(rs.getObject("locked_until", LocalDateTime.class))),
                gate, actor);
        return rows.isEmpty() ? null : rows.getFirst();
    }
    private void end(String id, Instant now, String reason) {
        jdbc.update("UPDATE admin_session SET ended_at=?,end_reason=? WHERE gate_hash=? AND id=? AND ended_at IS NULL", sql(now), reason, gate, id);
    }
    private String accountStatus(UUID accountId) {
        var rows = jdbc.query("SELECT status FROM identity_account WHERE gate_hash=? AND id=? FOR UPDATE",
                (rs, n) -> rs.getString(1), gate, accountId.toString());
        return rows.isEmpty() ? null : rows.getFirst();
    }
    /** Same gate row as the session service, so maintenance and HTTP operations are serialized. */
    private void lockGate() {
        Instant now = now();
        jdbc.update("INSERT INTO identity_session_gate (gate_hash,created_at,last_operation_at) VALUES (?,?,?) ON DUPLICATE KEY UPDATE gate_hash=gate_hash",
                gate, sql(now), sql(now));
        jdbc.queryForObject("SELECT gate_hash FROM identity_session_gate WHERE gate_hash=? FOR UPDATE", String.class, gate);
    }
    private byte[] secretAad(String actor, Instant created, String keyId) {
        return AdminCrypto.aad("waka-admin-totp-v1", gate, actor, created.toString(), keyId);
    }

    private static <T> T unwrap(Outcome<T> outcome) {
        if (outcome.refusal != null) throw new AdminException(outcome.refusal);
        return outcome.value;
    }
    private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MICROS); }
    private static Instant earliest(Instant a, Instant b) { return a.isBefore(b) ? a : b; }
    private static LocalDateTime sql(Instant value) { return LocalDateTime.ofInstant(value, ZoneOffset.UTC); }
    private static Instant instant(LocalDateTime value) { return value.toInstant(ZoneOffset.UTC); }
    private static Instant nullable(LocalDateTime value) { return value == null ? null : instant(value); }
    private static AdminException invalid() { return new AdminException(AdminException.Code.INVALID_REQUEST); }
    @Override public String toString() { return "PersistentAdminService[REDACTED]"; }
}
