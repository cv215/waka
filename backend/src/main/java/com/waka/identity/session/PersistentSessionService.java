package com.waka.identity.session;

import com.waka.identity.otp.OtpException;
import com.waka.identity.otp.PersistentOtpService;
import com.waka.identity.otp.PersistedOtpProofVerifier;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.function.Function;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Internal production core only. No bean, HTTP authentication, role or SMS activation. */
public final class PersistentSessionService {
    public record AuthenticatedSession(UUID accountId, UUID sessionId,
            Instant absoluteExpiresAt, Instant idleExpiresAt, Instant serverTime) {
        @Override public String toString() { return "AuthenticatedSession[REDACTED]"; }
    }
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final PersistedOtpProofVerifier proofVerifier;
    private final SessionCrypto crypto;
    private final SessionPolicy policy;
    private final Clock clock;
    private final String gate;
    private final SessionRecoveryPolicy recoveryPolicy;
    private final SessionRecoveryCrypto recoveryCrypto;

    public PersistentSessionService(JdbcTemplate jdbc, DataSourceTransactionManager manager,
            PersistedOtpProofVerifier proofVerifier, SessionCrypto crypto, SessionPolicy policy, Clock clock) {
        this(jdbc, manager, proofVerifier, crypto, policy, clock, null, null);
    }

    public PersistentSessionService(JdbcTemplate jdbc, DataSourceTransactionManager manager,
            PersistedOtpProofVerifier proofVerifier, SessionCrypto crypto, SessionPolicy policy, Clock clock,
            SessionRecoveryPolicy recoveryPolicy, SessionRecoveryCrypto recoveryCrypto) {
        this.jdbc = Objects.requireNonNull(jdbc);
        Objects.requireNonNull(manager);
        this.proofVerifier = Objects.requireNonNull(proofVerifier);
        this.crypto = Objects.requireNonNull(crypto);
        this.policy = Objects.requireNonNull(policy);
        this.clock = Objects.requireNonNull(clock);
        if ((recoveryPolicy == null) != (recoveryCrypto == null)
                || (recoveryCrypto != null && recoveryCrypto.reusesIdentityKey(crypto))) {
            throw new IllegalArgumentException("INVALID_SESSION_RECOVERY_DEPENDENCIES");
        }
        this.recoveryPolicy = recoveryPolicy;
        this.recoveryCrypto = recoveryCrypto;
        if (jdbc.getDataSource() == null || jdbc.getDataSource() != manager.getDataSource()
                || !proofVerifier.usesDataSource(jdbc.getDataSource()) || !proofVerifier.usesClock(clock)) {
            throw new IllegalArgumentException("SESSION_DEPENDENCIES_MISMATCH");
        }
        gate = crypto.gateHash();
        transaction = new TransactionTemplate(manager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public SessionGrant open(PersistentOtpService.Proof proof, String client, String phoneNumber) {
        return safe(() -> {
            validateClient(client);
            char[] token = crypto.newToken();
            try {
                String tokenHash = crypto.tokenHash(token);
                String contextHash = crypto.contextHash(client);
                Descriptor descriptor = transaction.execute(status -> {
                    lock();
                    var verified = proofVerifier.lockAndVerify(proof, client, phoneNumber);
                    Instant now = now();
                    if (!now.isBefore(verified.proofExpiresAt())) throw failure(SessionException.Code.INVALID_PROOF);
                    return create(proof, verified.normalizedPhone(), tokenHash, contextHash, now);
                });
                return new SessionGrant(descriptor.sessionId, descriptor.accountId, descriptor.absolute, descriptor.idle, token);
            } finally { Arrays.fill(token, '\0'); }
        });
    }

    /** Caller must first run OTP verifyBound with the same key, client, phone and code on every attempt. */
    public SessionGrant openOrReplay(PersistentOtpService.Proof proof, String client, String phoneNumber, UUID verifyKey) {
        return safe(() -> {
            validateClient(client);
            if (verifyKey == null) throw failure(SessionException.Code.INVALID_REQUEST);
            if (recoveryPolicy == null) throw failure(SessionException.Code.SERVICE_UNAVAILABLE);
            char[][] secret = { null };
            try {
                OpenOutcome outcome = transaction.execute(status -> {
                    lock();
                    var verified = proofVerifier.lockAndVerify(proof, client, phoneNumber, verifyKey);
                    Instant current = now();
                    if (!current.isBefore(verified.proofExpiresAt())) throw failure(SessionException.Code.INVALID_PROOF);
                    String request = crypto.recoveryRequestHash(client, verifyKey);
                    String context = crypto.contextHash(client);
                    String payload = crypto.recoveryPayloadHash(proof.challengeId(), proof.proofId(), context,
                            crypto.phoneHash(verified.normalizedPhone()), verified.verificationBinding());
                    Recovery previous = recovery(request);
                    if (previous != null) {
                        if (!SessionCrypto.equal(previous.payload, payload)) {
                            throw failure(SessionException.Code.IDEMPOTENCY_KEY_REUSED);
                        }
                        Row row = findById(previous.sessionId);
                        if (row == null || !SessionCrypto.equal(row.contextHash, context)
                                || !proof.challengeId().equals(row.challengeId) || !proof.proofId().equals(row.proofId)) {
                            throw failure(SessionException.Code.INVALID_PROOF);
                        }
                        if (!usable(row, current)) {
                            expireIfDue(row, current); eraseRecovery(row.id);
                            return new OpenOutcome(null, SessionException.Code.INVALID_SESSION);
                        }
                        if (!current.isBefore(previous.expires) || previous.cipher == null) {
                            eraseRecovery(row.id);
                            return new OpenOutcome(null, SessionException.Code.RECOVERY_EXPIRED);
                        }
                        if (!recoveryCrypto.keyId().equals(previous.keyId)) throw failure(SessionException.Code.SERVICE_UNAVAILABLE);
                        secret[0] = recoveryCrypto.decrypt(previous.cipher, aad(previous));
                        if (!SessionCrypto.equal(crypto.tokenHash(secret[0]), row.tokenHash)) {
                            throw failure(SessionException.Code.SERVICE_UNAVAILABLE);
                        }
                        // A response retry is not activity: no last_seen, idle or absolute deadline changes.
                        return new OpenOutcome(new Descriptor(row.id, row.accountId, row.absolute, row.idle), null);
                    }
                    secret[0] = crypto.newToken();
                    Descriptor descriptor = create(proof, verified.normalizedPhone(), crypto.tokenHash(secret[0]), context, current);
                    Instant expiry = earliest(current.plus(recoveryPolicy.lifetime()), verified.proofExpiresAt(),
                            descriptor.absolute, descriptor.idle);
                    Recovery created = new Recovery(request, payload, descriptor.sessionId, recoveryCrypto.keyId(), null, current, expiry);
                    byte[] encrypted = recoveryCrypto.encrypt(secret[0], aad(created));
                    try {
                        jdbc.update("INSERT INTO identity_session_recovery (request_hash,gate_hash,payload_mac,session_id,key_id,token_cipher,created_at,expires_at) VALUES (?,?,?,?,?,?,?,?)",
                                request, gate, payload, descriptor.sessionId.toString(), created.keyId, encrypted, sql(current), sql(expiry));
                    } finally { Arrays.fill(encrypted, (byte) 0); }
                    return new OpenOutcome(descriptor, null);
                });
                if (outcome.refusal != null) throw failure(outcome.refusal);
                Descriptor d = outcome.descriptor;
                return new SessionGrant(d.sessionId, d.accountId, d.absolute, d.idle, secret[0]);
            } finally { if (secret[0] != null) Arrays.fill(secret[0], '\0'); }
        });
    }

    public AuthenticatedSession authenticate(char[] token, String client) {
        return safe(() -> {
            validateClient(client);
            String tokenHash = crypto.tokenHash(token);
            String contextHash = crypto.contextHash(client);
            // An unknown random token must not contend on the shared session gate.
            // This indexed read grants nothing: an existing candidate is checked again under the gate.
            if (!tokenCandidateExists(tokenHash)) throw failure(SessionException.Code.INVALID_SESSION);
            AuthenticatedSession result = transaction.execute(status -> {
                lock(); Row row = find(tokenHash); Instant now = now();
                if (row == null || !SessionCrypto.equal(row.contextHash, contextHash)) return null;
                if (row.endedAt != null) { eraseRecovery(row.id); return null; }
                if (now.isBefore(row.lastSeenAt)) throw failure(SessionException.Code.SERVICE_UNAVAILABLE);
                if (!now.isBefore(row.absolute) || !now.isBefore(row.idle)) {
                    expireIfDue(row, now); eraseRecovery(row.id);
                    return null;
                }
                if (!"ACTIVE".equals(row.accountStatus)) { eraseRecovery(row.id); return null; }
                Instant idle = now.plus(row.idleMicros, ChronoUnit.MICROS);
                if (idle.isAfter(row.absolute)) idle = row.absolute;
                jdbc.update("UPDATE identity_session SET last_seen_at=?,idle_expires_at=? WHERE id=? AND gate_hash=?",
                        sql(now), sql(idle), row.id.toString(), gate);
                return new AuthenticatedSession(row.accountId, row.id, row.absolute, idle, now);
            });
            // Persist the expiry marker before reporting the refusal.
            if (result == null) throw failure(SessionException.Code.INVALID_SESSION);
            return result;
        });
    }

    /** Run an internal account operation under the same gate and transaction as revocation. */
    public <T> T withAuthenticatedSession(char[] token, String client,
            Function<AuthenticatedSession, T> operation) {
        Objects.requireNonNull(operation);
        return safe(() -> {
            validateClient(client);
            String tokenHash = crypto.tokenHash(token);
            String contextHash = crypto.contextHash(client);
            if (!tokenCandidateExists(tokenHash)) throw failure(SessionException.Code.INVALID_SESSION);
            AuthenticatedOutcome<T> result = transaction.execute(status -> {
                lock(); Row row = find(tokenHash); Instant current = now();
                if (row == null || !SessionCrypto.equal(row.contextHash, contextHash)) return null;
                if (row.endedAt != null) { eraseRecovery(row.id); return null; }
                if (current.isBefore(row.lastSeenAt)) throw failure(SessionException.Code.SERVICE_UNAVAILABLE);
                if (!current.isBefore(row.absolute) || !current.isBefore(row.idle)) {
                    expireIfDue(row, current); eraseRecovery(row.id); return null;
                }
                if (!"ACTIVE".equals(row.accountStatus)) { eraseRecovery(row.id); return null; }
                Instant idle = current.plus(row.idleMicros, ChronoUnit.MICROS);
                if (idle.isAfter(row.absolute)) idle = row.absolute;
                jdbc.update("UPDATE identity_session SET last_seen_at=?,idle_expires_at=? WHERE id=? AND gate_hash=?",
                        sql(current), sql(idle), row.id.toString(), gate);
                return new AuthenticatedOutcome<>(operation.apply(new AuthenticatedSession(
                        row.accountId, row.id, row.absolute, idle, current)));
            });
            if (result == null) throw failure(SessionException.Code.INVALID_SESSION);
            return result.value;
        });
    }

    public boolean usesDataSource(DataSource source) { return source != null && jdbc.getDataSource() == source; }
    public boolean usesClock(Clock candidate) { return clock == candidate; }
    public String identityGateHash() { return gate; }
    /** R14: whether a normalized number is the verified number of the account (withdrawals go only there). */
    public boolean isAccountPhone(UUID accountId, String normalizedPhone) {
        var macs = jdbc.query("SELECT phone_lookup_mac FROM identity_account WHERE gate_hash=? AND id=?",
                (rs, n) -> rs.getString(1), gate, accountId.toString());
        return !macs.isEmpty() && java.security.MessageDigest.isEqual(macs.getFirst().getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                crypto.phoneHash(normalizedPhone).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }
    public boolean reusesIdentityKey(byte[] key) { return crypto.matchesKey(key); }
    public boolean reusesSessionKey(byte[] key) {
        return crypto.matchesKey(key) || (recoveryCrypto != null && recoveryCrypto.matchesKey(key));
    }

    public void revoke(char[] token, String client) {
        revokeInternal(null, token, client, null);
    }

    public void revoke(UUID expectedSessionId, char[] token, String client) {
        if (expectedSessionId == null) throw failure(SessionException.Code.INVALID_REQUEST);
        revokeInternal(expectedSessionId, token, client, null);
    }

    public void revoke(UUID expectedSessionId, char[] token, String client, UUID revocationKey) {
        if (expectedSessionId == null || revocationKey == null) throw failure(SessionException.Code.INVALID_REQUEST);
        revokeInternal(expectedSessionId, token, client, revocationKey);
    }

    private void revokeInternal(UUID expectedSessionId, char[] token, String client, UUID revocationKey) {
        safe(() -> {
            validateClient(client);
            String tokenHash = crypto.tokenHash(token);
            String contextHash = crypto.contextHash(client);
            if (!tokenCandidateExists(tokenHash)) throw failure(SessionException.Code.INVALID_SESSION);
            SessionException.Code refusal = transaction.execute(status -> {
                lock(); Row row = find(tokenHash);
                if (row == null || !SessionCrypto.equal(row.contextHash, contextHash)
                        || (expectedSessionId != null && !expectedSessionId.equals(row.id))) return SessionException.Code.INVALID_SESSION;
                String request = revocationKey == null ? null : crypto.revocationRequestHash(client, revocationKey);
                if (request != null) {
                    var bound = jdbc.query("SELECT id FROM identity_session WHERE gate_hash=? AND revocation_key_hash=? FOR UPDATE",
                            (rs, n) -> UUID.fromString(rs.getString("id")), gate, request);
                    if (!bound.isEmpty() && !bound.getFirst().equals(row.id)) {
                        return SessionException.Code.IDEMPOTENCY_KEY_REUSED;
                    }
                    if (row.endedAt != null && (!"REVOKED".equals(row.endReason)
                            || !SessionCrypto.equal(row.revocationKey, request))) return SessionException.Code.INVALID_SESSION;
                }
                if (row.endedAt == null) {
                    Instant now = now();
                    if (now.isBefore(row.lastSeenAt)) throw failure(SessionException.Code.SERVICE_UNAVAILABLE);
                    jdbc.update("UPDATE identity_session SET ended_at=?,end_reason='REVOKED',revocation_key_hash=? WHERE id=? AND gate_hash=? AND ended_at IS NULL",
                            sql(now), request, row.id.toString(), gate);
                }
                eraseRecovery(row.id);
                return null;
            });
            if (refusal != null) throw failure(refusal);
            return Boolean.TRUE;
        });
    }

    /** Explicit maintenance hook; the runtime owner must schedule it before activation. Tombstones remain. */
    public int eraseExpiredRecoverySecrets() {
        return safe(() -> transaction.execute(status -> {
            lock(); Instant current = now();
            return jdbc.update("UPDATE identity_session_recovery r JOIN identity_session s ON s.id=r.session_id AND s.gate_hash=r.gate_hash JOIN identity_account a ON a.id=s.account_id AND a.gate_hash=s.gate_hash SET r.token_cipher=NULL WHERE r.gate_hash=? AND r.token_cipher IS NOT NULL AND (r.expires_at<=? OR s.ended_at IS NOT NULL OR s.absolute_expires_at<=? OR s.idle_expires_at<=? OR a.status<>'ACTIVE')",
                    gate, sql(current), sql(current), sql(current));
        }));
    }

    private Descriptor create(PersistentOtpService.Proof proof, String phone, String tokenHash, String contextHash, Instant current) {
        if (!jdbc.query("SELECT id FROM identity_session WHERE source_challenge_id=? OR source_proof_id=?",
                (rs, n) -> rs.getString("id"), proof.challengeId().toString(), proof.proofId().toString()).isEmpty()) {
            throw failure(SessionException.Code.PROOF_ALREADY_USED);
        }
        String phoneHash = crypto.phoneHash(phone);
        var accounts = jdbc.query("SELECT id,status FROM identity_account WHERE gate_hash=? AND phone_lookup_mac=? FOR UPDATE",
                (rs, n) -> new Account(UUID.fromString(rs.getString("id")), rs.getString("status")), gate, phoneHash);
        UUID accountId;
        if (accounts.isEmpty()) {
            accountId = UUID.randomUUID();
            jdbc.update("INSERT INTO identity_account (id,gate_hash,phone_lookup_mac,status,created_at,phone_verified_at) VALUES (?,?,?,'ACTIVE',?,?)",
                    accountId.toString(), gate, phoneHash, sql(current), sql(current));
        } else {
            Account account = accounts.getFirst();
            if (!"ACTIVE".equals(account.status)) throw failure(SessionException.Code.INVALID_SESSION);
            accountId = account.id;
            jdbc.update("UPDATE identity_account SET phone_verified_at=? WHERE id=? AND gate_hash=?",
                    sql(current), accountId.toString(), gate);
        }
        UUID sessionId = UUID.randomUUID();
        Instant absolute = current.plus(policy.absoluteLifetime()), idle = current.plus(policy.idleLifetime());
        // These unique source columns remain the durable marker, even after ciphertext erasure.
        jdbc.update("INSERT INTO identity_session (id,gate_hash,account_id,token_hash,context_hash,source_challenge_id,source_proof_id,created_at,last_seen_at,absolute_expires_at,idle_expires_at,idle_timeout_micros) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                sessionId.toString(), gate, accountId.toString(), tokenHash, contextHash, proof.challengeId().toString(),
                proof.proofId().toString(), sql(current), sql(current), sql(absolute), sql(idle), policy.idleMicros());
        return new Descriptor(sessionId, accountId, absolute, idle);
    }

    private boolean usable(Row row, Instant current) {
        if (current.isBefore(row.lastSeenAt)) throw failure(SessionException.Code.SERVICE_UNAVAILABLE);
        return row.endedAt == null && "ACTIVE".equals(row.accountStatus)
                && current.isBefore(row.absolute) && current.isBefore(row.idle);
    }
    private void expireIfDue(Row row, Instant current) {
        if (row.endedAt == null && (!current.isBefore(row.absolute) || !current.isBefore(row.idle))) {
            jdbc.update("UPDATE identity_session SET ended_at=?,end_reason='EXPIRED' WHERE id=? AND gate_hash=? AND ended_at IS NULL",
                    sql(current), row.id.toString(), gate);
        }
    }
    private void eraseRecovery(UUID sessionId) {
        jdbc.update("UPDATE identity_session_recovery SET token_cipher=NULL WHERE gate_hash=? AND session_id=? AND token_cipher IS NOT NULL",
                gate, sessionId.toString());
    }
    private Recovery recovery(String request) {
        var rows = jdbc.query("SELECT * FROM identity_session_recovery WHERE gate_hash=? AND request_hash=? FOR UPDATE",
                (rs, n) -> new Recovery(rs.getString("request_hash"), rs.getString("payload_mac"),
                        UUID.fromString(rs.getString("session_id")), rs.getString("key_id"), rs.getBytes("token_cipher"),
                        rs.getObject("created_at", LocalDateTime.class).toInstant(ZoneOffset.UTC),
                        rs.getObject("expires_at", LocalDateTime.class).toInstant(ZoneOffset.UTC)), gate, request);
        return rows.isEmpty() ? null : rows.getFirst();
    }
    private byte[] aad(Recovery recovery) {
        String[] values = { "waka-session-recovery-v1", gate, recovery.request, recovery.payload,
                recovery.sessionId.toString(), recovery.keyId, recovery.created.toString(), recovery.expires.toString() };
        byte[][] bytes = Arrays.stream(values).map(value -> value.getBytes(StandardCharsets.UTF_8)).toArray(byte[][]::new);
        int length = Arrays.stream(bytes).mapToInt(value -> 4 + value.length).sum();
        ByteBuffer result = ByteBuffer.allocate(length);
        for (byte[] value : bytes) result.putInt(value.length).put(value);
        return result.array();
    }
    private static Instant earliest(Instant... times) { return Arrays.stream(times).min(Instant::compareTo).orElseThrow(); }

    private void lock() {
        Instant beforeLock = now();
        jdbc.update("INSERT INTO identity_session_gate (gate_hash,created_at,last_operation_at) VALUES (?,?,?) ON DUPLICATE KEY UPDATE gate_hash=gate_hash",
                gate, sql(beforeLock), sql(beforeLock));
        LocalDateTime last = jdbc.queryForObject("SELECT last_operation_at FROM identity_session_gate WHERE gate_hash=? FOR UPDATE",
                LocalDateTime.class, gate);
        Instant current = now();
        if (current.isBefore(last.toInstant(ZoneOffset.UTC))) throw failure(SessionException.Code.SERVICE_UNAVAILABLE);
        jdbc.update("UPDATE identity_session_gate SET last_operation_at=? WHERE gate_hash=?", sql(current), gate);
    }
    private Row find(String tokenHash) {
        List<Row> rows = jdbc.query("SELECT s.*,a.status AS account_status FROM identity_session s JOIN identity_account a ON a.id=s.account_id AND a.gate_hash=s.gate_hash WHERE s.gate_hash=? AND s.token_hash=? FOR UPDATE",
                rowMapper(), gate, tokenHash);
        return rows.isEmpty() ? null : rows.getFirst();
    }
    private boolean tokenCandidateExists(String tokenHash) {
        return !jdbc.query("SELECT 1 FROM identity_session WHERE gate_hash=? AND token_hash=?",
                (rs, n) -> rs.getInt(1), gate, tokenHash).isEmpty();
    }
    private Row findById(UUID id) {
        List<Row> rows = jdbc.query("SELECT s.*,a.status AS account_status FROM identity_session s JOIN identity_account a ON a.id=s.account_id AND a.gate_hash=s.gate_hash WHERE s.gate_hash=? AND s.id=? FOR UPDATE",
                rowMapper(), gate, id.toString());
        return rows.isEmpty() ? null : rows.getFirst();
    }
    private static RowMapper<Row> rowMapper() {
        return (rs, n) -> new Row(UUID.fromString(rs.getString("id")), UUID.fromString(rs.getString("account_id")),
                        rs.getString("context_hash"), rs.getObject("last_seen_at", LocalDateTime.class).toInstant(ZoneOffset.UTC),
                        rs.getObject("absolute_expires_at", LocalDateTime.class).toInstant(ZoneOffset.UTC),
                        rs.getObject("idle_expires_at", LocalDateTime.class).toInstant(ZoneOffset.UTC),
                        rs.getLong("idle_timeout_micros"), rs.getObject("ended_at", LocalDateTime.class),
                        rs.getString("account_status"), rs.getString("token_hash"), rs.getString("end_reason"),
                        rs.getString("revocation_key_hash"), UUID.fromString(rs.getString("source_challenge_id")),
                        UUID.fromString(rs.getString("source_proof_id")));
    }
    private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MICROS); }
    private static LocalDateTime sql(Instant instant) { return LocalDateTime.ofInstant(instant, ZoneOffset.UTC); }
    private static void validateClient(String client) {
        if (client == null || !client.matches("[A-Za-z0-9_-]{16,128}")) throw failure(SessionException.Code.INVALID_REQUEST);
    }
    private static SessionException failure(SessionException.Code code) { return new SessionException(code); }
    private static <T> T safe(Supplier<T> operation) {
        try {
            if (TransactionSynchronizationManager.isActualTransactionActive()) {
                throw failure(SessionException.Code.SERVICE_UNAVAILABLE);
            }
            return operation.get();
        } catch (SessionException known) { throw known; }
        catch (OtpException invalid) {
            throw failure(invalid.code() == OtpException.Code.VERIFICATION_INVALID
                    || invalid.code() == OtpException.Code.INVALID_REQUEST
                    ? SessionException.Code.INVALID_PROOF : SessionException.Code.SERVICE_UNAVAILABLE);
        } catch (RuntimeException hidden) { throw failure(SessionException.Code.SERVICE_UNAVAILABLE); }
    }
    private record Descriptor(UUID sessionId, UUID accountId, Instant absolute, Instant idle) { }
    private record OpenOutcome(Descriptor descriptor, SessionException.Code refusal) { }
    private record AuthenticatedOutcome<T>(T value) { }
    private record Recovery(String request, String payload, UUID sessionId, String keyId, byte[] cipher,
                            Instant created, Instant expires) {
        @Override public String toString() { return "SessionRecoveryRow[REDACTED]"; }
    }
    private record Account(UUID id, String status) { }
    private record Row(UUID id, UUID accountId, String contextHash, Instant lastSeenAt,
                       Instant absolute, Instant idle, long idleMicros, LocalDateTime endedAt, String accountStatus,
                       String tokenHash, String endReason, String revocationKey, UUID challengeId, UUID proofId) {
        @Override public String toString() { return "SessionRow[REDACTED]"; }
    }
}

