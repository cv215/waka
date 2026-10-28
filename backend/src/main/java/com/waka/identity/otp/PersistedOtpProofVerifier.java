package com.waka.identity.otp;

import com.waka.identity.application.CameroonMobilePhoneFormat;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Read/lock bridge only. No SMS dependency, issuance, public crypto helper or bean. */
public final class PersistedOtpProofVerifier {
    private final JdbcTemplate jdbc;
    private final OtpCrypto crypto;
    private final Clock clock;
    public PersistedOtpProofVerifier(JdbcTemplate jdbc, OtpCrypto crypto, Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.crypto = Objects.requireNonNull(crypto);
        this.clock = Objects.requireNonNull(clock);
        Objects.requireNonNull(jdbc.getDataSource());
    }
    public boolean usesDataSource(DataSource dataSource) { return jdbc.getDataSource() == dataSource; }
    public boolean usesClock(Clock clock) { return this.clock == clock; }

    public VerifiedPhone lockAndVerify(PersistentOtpService.Proof proof, String client, String phoneNumber) {
        return lockAndVerifyInternal(proof, client, phoneNumber, null);
    }

    public VerifiedPhone lockAndVerify(PersistentOtpService.Proof proof, String client, String phoneNumber,
            java.util.UUID verifyKey) {
        if (verifyKey == null) throw invalid();
        return lockAndVerifyInternal(proof, client, phoneNumber, verifyKey);
    }

    private VerifiedPhone lockAndVerifyInternal(PersistentOtpService.Proof proof, String client, String phoneNumber,
            java.util.UUID verifyKey) {
        DataSource source = jdbc.getDataSource();
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.hasResource(source)) {
            throw new OtpException(OtpException.Code.SERVICE_UNAVAILABLE);
        }
        if (proof == null || proof.challengeId() == null || proof.proofId() == null
                || client == null || !client.matches("[A-Za-z0-9_-]{16,128}")) throw invalid();
        String phone;
        try { phone = CameroonMobilePhoneFormat.normalize(phoneNumber); }
        catch (IllegalArgumentException failure) { throw invalid(); }
        var found = jdbc.query("SELECT proof_id,context_hash,destination_hash,expires_at,lifecycle,submission,verify_key_mac,verify_code_mac FROM otp_challenge WHERE id=? FOR UPDATE",
                (rs, n) -> new Row(rs.getString("proof_id"), rs.getString("context_hash"),
                        rs.getString("destination_hash"), rs.getObject("expires_at", LocalDateTime.class).toInstant(ZoneOffset.UTC),
                        rs.getString("lifecycle"), rs.getString("submission"), rs.getString("verify_key_mac"), rs.getString("verify_code_mac")),
                proof.challengeId().toString());
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        if (found.size() != 1) throw invalid();
        Row row = found.getFirst();
        if (!"CONSUMED".equals(row.lifecycle) || !("ACCEPTED".equals(row.submission) || "UNKNOWN".equals(row.submission))
                || !now.isBefore(row.expiresAt)
                || !proof.proofId().toString().equals(row.proofId) || row.verifyKeyMac == null || row.verifyCodeMac == null
                || !OtpCrypto.equal(row.contextHash, crypto.fingerprint("otp-context-v1", client))
                || !OtpCrypto.equal(row.destinationHash, crypto.fingerprint("otp-destination-v1", phone))) throw invalid();
        if (verifyKey != null && !OtpCrypto.equal(row.verifyKeyMac,
                crypto.fingerprint("otp-verify-v1", client, verifyKey.toString()))) throw invalid();
        // The DTO's expiry is deliberately not trusted; the database controls validity.
        return new VerifiedPhone(phone, row.expiresAt, row.verifyCodeMac);
    }
    public record VerifiedPhone(String normalizedPhone, Instant proofExpiresAt, String verificationBinding) {
        @Override public String toString() { return "VerifiedPhone[REDACTED]"; }
    }
    private record Row(String proofId, String contextHash, String destinationHash, Instant expiresAt,
                       String lifecycle, String submission, String verifyKeyMac, String verifyCodeMac) {
        @Override public String toString() { return "PersistedOtpProofRow[REDACTED]"; }
    }
    private static OtpException invalid() { return new OtpException(OtpException.Code.VERIFICATION_INVALID); }
}

