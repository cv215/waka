package com.waka.identity.otp;

import com.waka.identity.application.CameroonMobilePhoneFormat;
import java.sql.ResultSet;
import java.sql.SQLException;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Explicitly constructed production component; deliberately no Spring stereotype or route. */
public final class PersistentOtpService {
    public enum Submission { SENDING, ACCEPTED, REJECTED, UNKNOWN }
    public record Challenge(UUID id, Submission submission, Instant expiresAt) { }
    /** Ephemeral internal proof only: no account/session/right is created or accepted here. */
    public record Proof(UUID challengeId, UUID proofId, Instant expiresAt) {
        @Override public String toString() { return "OtpProof[REDACTED]"; }
    }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final OtpCrypto crypto;
    private final OtpPolicy policy;
    private final SmsGateway gateway;
    private final Clock clock;
    private final String globalGate;

    public PersistentOtpService(JdbcTemplate jdbc, PlatformTransactionManager manager,
            OtpCrypto crypto, OtpPolicy policy, SmsGateway gateway, Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.crypto = Objects.requireNonNull(crypto); this.policy = Objects.requireNonNull(policy);
        this.gateway = Objects.requireNonNull(gateway); this.clock = Objects.requireNonNull(clock);
        this.globalGate = crypto.fingerprint("otp-global-gate-v1");
        this.transaction = new TransactionTemplate(Objects.requireNonNull(manager));
        this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public Challenge request(String clientInstanceRef, UUID requestKey, String phoneNumber) {
        return safe(() -> requestInternal(clientInstanceRef, requestKey, phoneNumber));
    }

    private Challenge requestInternal(String client, UUID key, String phoneNumber) {
        // Forbid an enclosing transaction: no caller may accidentally hold one over transport.
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw unavailable();
        validate(client, key);
        String destination;
        try { destination = CameroonMobilePhoneFormat.normalize(phoneNumber); }
        catch (IllegalArgumentException invalid) { throw failure(OtpException.Code.INVALID_REQUEST); }
        String contextHash = crypto.fingerprint("otp-context-v1", client);
        String destinationHash = crypto.fingerprint("otp-destination-v1", destination);
        String requestHash = crypto.fingerprint("otp-request-v1", client, key.toString());
        UUID id = UUID.randomUUID();
        char[] code = crypto.newCode();
        char[] contact = destination.toCharArray();
        try {
            Reservation reservation = transaction.execute(status -> {
                lock(); Instant now = now(); expire(now);
                List<Row> existing = rows("SELECT * FROM otp_challenge WHERE request_hash=?", requestHash);
                if (!existing.isEmpty()) {
                    Row row = existing.getFirst();
                    if (!OtpCrypto.equal(row.destinationHash, destinationHash)) {
                        throw failure(OtpException.Code.IDEMPOTENCY_KEY_REUSED);
                    }
                    return new Reservation(row.challenge(), false);
                }
                if (policy.hourlyBudget() == 0 || count("SELECT COUNT(*) FROM otp_challenge WHERE global_gate=? AND created_at>?",
                        globalGate, sql(now.minus(1, ChronoUnit.HOURS))) >= policy.hourlyBudget()) {
                    throw failure(OtpException.Code.BUDGET_EXHAUSTED);
                }
                Instant window = now.minus(policy.rateWindow());
                if (count("SELECT COUNT(*) FROM otp_challenge WHERE destination_hash=? AND created_at>?", destinationHash, sql(window)) >= policy.destinationRequests()
                        || count("SELECT COUNT(*) FROM otp_challenge WHERE context_hash=? AND created_at>?", contextHash, sql(window)) >= policy.contextRequests()
                        || failureCount(destinationHash, window) >= policy.destinationFailures()
                        || count("SELECT COUNT(*) FROM otp_challenge WHERE destination_hash=? AND created_at>?", destinationHash, sql(now.minus(policy.cooldown()))) > 0
                        || count("SELECT COUNT(*) FROM otp_challenge WHERE destination_hash=? AND lifecycle='ACTIVE' AND submission IN ('SENDING','UNKNOWN')", destinationHash) > 0) {
                    throw failure(OtpException.Code.RATE_LIMITED);
                }
                Instant expiry = now.plus(policy.lifetime());
                // A newly admitted code permanently replaces the prior accepted one, even if
                // transport subsequently becomes uncertain/rejected. Never restore an old code.
                jdbc.update("UPDATE otp_challenge SET lifecycle='SUPERSEDED',code_mac=NULL,destination_cipher=NULL WHERE destination_hash=? AND lifecycle='ACTIVE' AND submission='ACCEPTED'", destinationHash);
                jdbc.update("INSERT INTO otp_challenge (id,global_gate,request_hash,context_hash,destination_hash,destination_cipher,code_mac,created_at,expires_at,submission,lifecycle,wrong_attempts) VALUES (?,?,?,?,?,?,?,?,?,'SENDING','ACTIVE',0)",
                        id.toString(), globalGate, requestHash, contextHash, destinationHash,
                        crypto.encryptDestination(id, contact), crypto.codeFingerprint(id, contextHash, code), sql(now), sql(expiry));
                return new Reservation(new Challenge(id, Submission.SENDING, expiry), true);
            });
            if (!reservation.created) return reservation.challenge;
            SmsGateway.Submission result;
            try (SmsMessage message = new SmsMessage(id, contact, code)) {
                try { result = gateway.send(message); }
                catch (Exception uncertain) { result = SmsGateway.Submission.UNKNOWN; }
            }
            Submission submission = result == null ? Submission.UNKNOWN : Submission.valueOf(result.name());
            // No retry of this provider call, including if this persistence step fails or crashes.
            return transaction.execute(status -> {
                lock(); Instant now = now(); expire(now);
                Row row = rows("SELECT * FROM otp_challenge WHERE id=?", id.toString()).getFirst();
                jdbc.update("UPDATE otp_challenge SET submission=?,destination_cipher=NULL,code_mac=CASE WHEN ?='REJECTED' THEN NULL ELSE code_mac END,lifecycle=CASE WHEN ?='REJECTED' AND lifecycle='ACTIVE' THEN 'LOCKED' ELSE lifecycle END WHERE id=? AND submission='SENDING'",
                        submission.name(), submission.name(), submission.name(), id.toString());
                return new Challenge(id, submission, row.expiresAt);
            });
        } finally { Arrays.fill(code, '\0'); Arrays.fill(contact, '\0'); }
    }

    public Proof verify(UUID challengeId, String clientInstanceRef, UUID verifyKey, char[] suppliedCode) {
        return safe(() -> verifyInternal(challengeId, clientInstanceRef, verifyKey, suppliedCode, null));
    }

    /** HTTP login path: bind the repeated phone before consuming the proof, including on retries. */
    public Proof verifyBound(UUID challengeId, String clientInstanceRef, UUID verifyKey,
            String phoneNumber, char[] suppliedCode) {
        return safe(() -> {
            String phone;
            try { phone = CameroonMobilePhoneFormat.normalize(phoneNumber); }
            catch (IllegalArgumentException invalid) { throw failure(OtpException.Code.INVALID_REQUEST); }
            return verifyInternal(challengeId, clientInstanceRef, verifyKey, suppliedCode, phone);
        });
    }

    private Proof verifyInternal(UUID id, String client, UUID key, char[] suppliedCode, String boundPhone) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw unavailable();
        validate(client, key);
        if (id == null || suppliedCode == null || suppliedCode.length != 6) throw failure(OtpException.Code.INVALID_REQUEST);
        char[] code = suppliedCode.clone();
        try {
            for (char digit : code) if (digit < '0' || digit > '9') throw failure(OtpException.Code.INVALID_REQUEST);
            String contextHash = crypto.fingerprint("otp-context-v1", client);
            String keyHash = crypto.fingerprint("otp-verify-v1", client, key.toString());
            String codeHash = crypto.codeFingerprint(id, contextHash, code);
            String destinationHash = boundPhone == null ? null : crypto.fingerprint("otp-destination-v1", boundPhone);
            String payloadHash = boundPhone == null ? codeHash
                    : crypto.fingerprint("otp-bound-verification-v1", codeHash, destinationHash);
            Verification result = transaction.execute(status -> {
                lock(); Instant now = now(); expire(now);
                List<VerificationReplay> replays = jdbc.query("SELECT payload_mac,proof_id,expires_at FROM otp_verification WHERE key_hash=?", (rs, n) ->
                        new VerificationReplay(rs.getString("payload_mac"), rs.getString("proof_id"),
                                rs.getObject("expires_at", LocalDateTime.class)), keyHash);
                if (!replays.isEmpty()) {
                    VerificationReplay replay = replays.getFirst();
                    if (!OtpCrypto.equal(replay.payloadMac, payloadHash)) throw failure(OtpException.Code.IDEMPOTENCY_KEY_REUSED);
                    if (destinationHash != null) {
                        List<Row> bound = rows("SELECT * FROM otp_challenge WHERE id=? FOR UPDATE", id.toString());
                        if (bound.size() != 1 || !OtpCrypto.equal(bound.getFirst().contextHash, contextHash)
                                || !OtpCrypto.equal(bound.getFirst().destinationHash, destinationHash)) return Verification.invalid();
                    }
                    return replay.proofId == null ? Verification.invalid() : new Verification(
                            new Proof(id, UUID.fromString(replay.proofId), replay.expiry.toInstant(ZoneOffset.UTC)));
                }
                List<Row> found = rows("SELECT * FROM otp_challenge WHERE id=? FOR UPDATE", id.toString());
                if (found.isEmpty()) return Verification.invalid();
                Row row = found.getFirst();
                if (!OtpCrypto.equal(row.contextHash, contextHash)) return Verification.invalid();
                if (destinationHash != null && !OtpCrypto.equal(row.destinationHash, destinationHash)) return Verification.invalid();
                if (row.lifecycle.equals("CONSUMED")) {
                    if (OtpCrypto.equal(row.verifyKeyMac, keyHash) && OtpCrypto.equal(row.verifyCodeMac, codeHash)) {
                        return new Verification(new Proof(id, UUID.fromString(row.proofId), row.expiresAt));
                    }
                    return Verification.invalid();
                }
                if (!row.lifecycle.equals("ACTIVE") || !now.isBefore(row.expiresAt)
                        || row.submission == Submission.REJECTED || row.submission == Submission.SENDING) return Verification.invalid();
                if (failureCount(row.destinationHash, now.minus(policy.rateWindow())) >= policy.destinationFailures()) return Verification.invalid();
                jdbc.update("INSERT INTO otp_verification (key_hash,global_gate,payload_mac) VALUES (?,?,?)", keyHash, globalGate, payloadHash);
                if (!OtpCrypto.equal(row.codeMac, codeHash)) {
                    jdbc.update("INSERT INTO otp_rate_event (id,global_gate,destination_hash,created_at) VALUES (?,?,?,?)",
                            UUID.randomUUID().toString(), globalGate, row.destinationHash, sql(now));
                    jdbc.update("UPDATE otp_challenge SET lifecycle=CASE WHEN wrong_attempts+1>=? THEN 'LOCKED' ELSE lifecycle END,code_mac=CASE WHEN wrong_attempts+1>=? THEN NULL ELSE code_mac END,destination_cipher=NULL,wrong_attempts=wrong_attempts+1 WHERE id=?",
                            policy.maximumWrongAttempts(), policy.maximumWrongAttempts(), id.toString());
                    return Verification.invalid();
                }
                UUID proofId = UUID.randomUUID();
                jdbc.update("UPDATE otp_challenge SET lifecycle='CONSUMED',code_mac=NULL,destination_cipher=NULL,verify_key_mac=?,verify_code_mac=?,proof_id=? WHERE id=?",
                        keyHash, codeHash, proofId.toString(), id.toString());
                jdbc.update("UPDATE otp_verification SET proof_id=?,expires_at=? WHERE key_hash=?", proofId.toString(), sql(row.expiresAt), keyHash);
                return new Verification(new Proof(id, proofId, row.expiresAt));
            });
            // Failure counters must commit, so refuse only after transaction completion.
            if (result.proof == null) throw failure(OtpException.Code.VERIFICATION_INVALID);
            return result.proof;
        } finally { Arrays.fill(code, '\0'); }
    }

    /** Explicit maintenance hook; no scheduled task is installed. Retains non-secret replay evidence. */
    public void eraseExpiredSecrets() {
        safe(() -> transaction.execute(status -> { lock(); expire(now()); return Boolean.TRUE; }));
    }

    private void lock() {
        jdbc.update("INSERT INTO otp_gate (gate_hash,created_at) VALUES (?,?) ON DUPLICATE KEY UPDATE gate_hash=gate_hash", globalGate, sql(now()));
        jdbc.queryForObject("SELECT gate_hash FROM otp_gate WHERE gate_hash=? FOR UPDATE", String.class, globalGate);
    }
    private void expire(Instant now) {
        jdbc.update("UPDATE otp_challenge SET code_mac=NULL,destination_cipher=NULL,lifecycle='EXPIRED' WHERE global_gate=? AND lifecycle='ACTIVE' AND expires_at<=?", globalGate, sql(now));
    }
    private int failureCount(String destination, Instant since) {
        return count("SELECT COUNT(*) FROM otp_rate_event WHERE destination_hash=? AND created_at>?", destination, sql(since));
    }
    private int count(String sql, Object... args) { return jdbc.queryForObject(sql, Integer.class, args); }
    private List<Row> rows(String sql, Object... args) { return jdbc.query(sql, (rs, n) -> row(rs), args); }
    private static Row row(ResultSet rs) throws SQLException {
        return new Row(UUID.fromString(rs.getString("id")), rs.getString("context_hash"), rs.getString("destination_hash"),
                rs.getString("code_mac"), rs.getObject("expires_at", LocalDateTime.class).toInstant(ZoneOffset.UTC),
                Submission.valueOf(rs.getString("submission")), rs.getString("lifecycle"), rs.getString("verify_key_mac"),
                rs.getString("verify_code_mac"), rs.getString("proof_id"));
    }
    private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MICROS); }
    private static LocalDateTime sql(Instant instant) { return LocalDateTime.ofInstant(instant, ZoneOffset.UTC); }
    private static void validate(String client, UUID key) {
        if (key == null || client == null || !client.matches("[A-Za-z0-9_-]{16,128}")) throw failure(OtpException.Code.INVALID_REQUEST);
    }
    private static OtpException failure(OtpException.Code code) { return new OtpException(code); }
    private static OtpException unavailable() { return failure(OtpException.Code.SERVICE_UNAVAILABLE); }
    private static <T> T safe(Supplier<T> operation) {
        try { return operation.get(); }
        catch (OtpException known) { throw known; }
        catch (RuntimeException hidden) { throw unavailable(); }
    }
    private record Reservation(Challenge challenge, boolean created) { }
    private record Verification(Proof proof) { static Verification invalid() { return new Verification(null); } }
    private record VerificationReplay(String payloadMac, String proofId, LocalDateTime expiry) {
        @Override public String toString() { return "OtpVerificationReplay[REDACTED]"; }
    }
    private record Row(UUID id, String contextHash, String destinationHash, String codeMac,
            Instant expiresAt, Submission submission, String lifecycle, String verifyKeyMac,
            String verifyCodeMac, String proofId) {
        Challenge challenge() { return new Challenge(id, submission, expiresAt); }
        @Override public String toString() { return "OtpRow[REDACTED]"; }
    }
}
