package com.waka.identity.profile;

import com.waka.identity.session.PersistentSessionService;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Internal profile core. Construct explicitly after qualifying keys and maintenance policy. */
public final class PersistentProfileService {
    public record ProfileView(UUID accountId, long version, String displayName, Instant updatedAt,
            Instant serverTime, boolean idempotentReplay) {
        @Override public String toString() { return "ProfileView[REDACTED]"; }
    }
    private record ProfileRow(long version, String keyId, byte[] cipher, Instant updatedAt) {
        @Override public String toString() { return "ProfileRow[REDACTED]"; }
    }
    private record CommandRow(String payloadMac, String keyId, byte[] cipher, Instant createdAt, Instant expiresAt) {
        @Override public String toString() { return "ProfileCommandRow[REDACTED]"; }
    }
    private record Outcome(ProfileView view, ProfileException.Code refusal) { }
    private final PersistentSessionService sessions;
    private final JdbcTemplate jdbc;
    private final ProfileCrypto crypto;
    private final Duration responseLifetime;
    private final String gate;
    private final TransactionTemplate maintenance;
    private final Clock clock;

    public PersistentProfileService(PersistentSessionService sessions, JdbcTemplate jdbc,
            DataSourceTransactionManager manager, ProfileCrypto crypto, Duration responseLifetime, Clock clock) {
        this.sessions = Objects.requireNonNull(sessions);
        this.jdbc = Objects.requireNonNull(jdbc);
        this.crypto = Objects.requireNonNull(crypto);
        this.clock = Objects.requireNonNull(clock);
        if (jdbc.getDataSource() == null || jdbc.getDataSource() != Objects.requireNonNull(manager).getDataSource()
                || !sessions.usesDataSource(jdbc.getDataSource()) || !sessions.usesClock(clock))
            throw new IllegalArgumentException("PROFILE_DEPENDENCIES_MISMATCH");
        if (crypto.reusesSessionKey(sessions)) throw new IllegalArgumentException("PROFILE_SESSION_KEY_REUSE");
        if (responseLifetime == null || responseLifetime.isNegative() || responseLifetime.isZero()
                || responseLifetime.getNano() % 1000 != 0
                || responseLifetime.compareTo(Duration.ofDays(365)) > 0) {
            throw new IllegalArgumentException("PROFILE_RESPONSE_LIFETIME_REQUIRED");
        }
        this.responseLifetime = responseLifetime;
        this.gate = sessions.identityGateHash();
        maintenance = new TransactionTemplate(manager);
        maintenance.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        maintenance.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public ProfileView read(char[] token, String client) {
        Outcome outcome = sessions.withAuthenticatedSession(token, client, session -> {
            ProfileRow row = profile(session.accountId());
            if (row == null) return new Outcome(new ProfileView(session.accountId(), 1, null, null,
                    session.serverTime(), false), null);
            String name = decryptName(session.accountId(), row);
            return new Outcome(new ProfileView(session.accountId(), row.version, name, row.updatedAt,
                    session.serverTime(), false), null);
        });
        return unwrap(outcome);
    }

    public ProfileView patch(char[] token, String client, UUID key, long expectedVersion, String displayName) {
        if (key == null || expectedVersion < 1 || expectedVersion > 9007199254740991L) {
            throw new ProfileException(ProfileException.Code.INVALID_REQUEST);
        }
        String name = ProfileName.normalize(displayName);
        Outcome outcome = sessions.withAuthenticatedSession(token, client, session -> {
            UUID account = session.accountId();
            String actor = account.toString();
            String request = crypto.requestMac(actor, "PATCH /api/v1/me", key.toString());
            String payload = crypto.payloadMac(actor, expectedVersion, name);
            CommandRow previous = command(request);
            if (previous != null) {
                if (!previous.payloadMac.equals(payload)) return new Outcome(null, ProfileException.Code.IDEMPOTENCY_KEY_REUSED);
                if (!session.serverTime().isBefore(previous.expiresAt) || previous.cipher == null) {
                    eraseCommand(request);
                    return new Outcome(null, ProfileException.Code.REPLAY_EXPIRED);
                }
                if (!crypto.keyId().equals(previous.keyId)) throw unavailable();
                byte[] plain = crypto.decrypt(previous.cipher, commandAad(actor, request, payload, previous));
                try { return new Outcome(decodeResponse(account, plain, true), null); }
                finally { Arrays.fill(plain, (byte) 0); }
            }
            ProfileRow current = profile(account);
            long version = current == null ? 1 : current.version;
            if (expectedVersion != version || version == 9007199254740991L) {
                return new Outcome(null, ProfileException.Code.VERSION_CONFLICT);
            }
            long next = version + 1;
            Instant now = session.serverTime();
            byte[] namePlain = encodeName(name);
            byte[] nameCipher;
            try { nameCipher = crypto.encrypt(namePlain, nameAad(actor, next, now, crypto.keyId())); }
            finally { Arrays.fill(namePlain, (byte) 0); }
            try {
                if (current == null) {
                    jdbc.update("INSERT INTO identity_profile (gate_hash,account_id,version,key_id,display_cipher,updated_at) VALUES (?,?,?,?,?,?)",
                            gate, actor, next, crypto.keyId(), nameCipher, sql(now));
                } else {
                    int changed = jdbc.update("UPDATE identity_profile SET version=?,key_id=?,display_cipher=?,updated_at=? WHERE gate_hash=? AND account_id=? AND version=?",
                            next, crypto.keyId(), nameCipher, sql(now), gate, actor, version);
                    if (changed != 1) return new Outcome(null, ProfileException.Code.VERSION_CONFLICT);
                }
            } finally { Arrays.fill(nameCipher, (byte) 0); }
            ProfileView view = new ProfileView(account, next, name, now, now, false);
            Instant expires = now.plus(responseLifetime);
            CommandRow created = new CommandRow(payload, crypto.keyId(), null, now, expires);
            byte[] responsePlain = encodeResponse(view);
            byte[] responseCipher;
            try { responseCipher = crypto.encrypt(responsePlain, commandAad(actor, request, payload, created)); }
            finally { Arrays.fill(responsePlain, (byte) 0); }
            try {
                jdbc.update("INSERT INTO identity_profile_command (request_mac,gate_hash,account_id,payload_mac,key_id,response_cipher,created_at,expires_at) VALUES (?,?,?,?,?,?,?,?)",
                        request, gate, actor, payload, crypto.keyId(), responseCipher, sql(now), sql(expires));
            } finally { Arrays.fill(responseCipher, (byte) 0); }
            return new Outcome(view, null);
        });
        return unwrap(outcome);
    }

    /** Explicitly scheduled by the runtime owner; retains request markers. */
    public int eraseExpiredResponseSecrets() {
        return maintenance.execute(status -> jdbc.update(
                "UPDATE identity_profile_command SET response_cipher=NULL WHERE gate_hash=? AND response_cipher IS NOT NULL AND expires_at<=?",
                gate, sql(clock.instant())));
    }

    private ProfileRow profile(UUID account) {
        var rows = jdbc.query("SELECT version,key_id,display_cipher,updated_at FROM identity_profile WHERE gate_hash=? AND account_id=? FOR UPDATE",
                (rs, n) -> new ProfileRow(rs.getLong("version"), rs.getString("key_id"),
                        rs.getBytes("display_cipher"), instant(rs.getObject("updated_at", LocalDateTime.class))),
                gate, account.toString());
        return rows.isEmpty() ? null : rows.getFirst();
    }
    private CommandRow command(String request) {
        var rows = jdbc.query("SELECT payload_mac,key_id,response_cipher,created_at,expires_at FROM identity_profile_command WHERE gate_hash=? AND request_mac=? FOR UPDATE",
                (rs, n) -> new CommandRow(rs.getString("payload_mac"), rs.getString("key_id"),
                        rs.getBytes("response_cipher"), instant(rs.getObject("created_at", LocalDateTime.class)),
                        instant(rs.getObject("expires_at", LocalDateTime.class))), gate, request);
        return rows.isEmpty() ? null : rows.getFirst();
    }
    private void eraseCommand(String request) {
        jdbc.update("UPDATE identity_profile_command SET response_cipher=NULL WHERE gate_hash=? AND request_mac=? AND response_cipher IS NOT NULL",
                gate, request);
    }
    private String decryptName(UUID account, ProfileRow row) {
        if (!crypto.keyId().equals(row.keyId)) throw unavailable();
        byte[] plain = crypto.decrypt(row.cipher, nameAad(account.toString(), row.version, row.updatedAt, row.keyId));
        try {
            if (plain.length == 1 && plain[0] == 0) return null;
            if (plain.length < 2 || plain[0] != 1) throw unavailable();
            String name = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(plain, 1, plain.length - 1)).toString();
            if (!name.equals(ProfileName.normalize(name))) throw unavailable();
            return name;
        } catch (java.nio.charset.CharacterCodingException hidden) { throw unavailable(); }
        finally { Arrays.fill(plain, (byte) 0); }
    }
    private static byte[] encodeName(String name) {
        if (name == null) return new byte[] { 0 };
        byte[] utf8 = name.getBytes(StandardCharsets.UTF_8);
        byte[] plain = new byte[utf8.length + 1];
        plain[0] = 1; System.arraycopy(utf8, 0, plain, 1, utf8.length);
        Arrays.fill(utf8, (byte) 0);
        return plain;
    }
    private byte[] nameAad(String actor, long version, Instant updated, String keyId) {
        return ProfileCrypto.aad("waka-profile-name-v1", gate, actor, Long.toString(version), updated.toString(), keyId);
    }
    private byte[] commandAad(String actor, String request, String payload, CommandRow row) {
        return ProfileCrypto.aad("waka-profile-response-v1", gate, actor, request, payload,
                row.keyId, row.createdAt.toString(), row.expiresAt.toString());
    }
    private static byte[] encodeResponse(ProfileView view) {
        try {
            var bytes = new ByteArrayOutputStream(); var output = new DataOutputStream(bytes);
            output.writeLong(view.version); output.writeBoolean(view.displayName != null);
            if (view.displayName != null) output.writeUTF(view.displayName);
            output.writeUTF(view.updatedAt.toString()); output.writeUTF(view.serverTime.toString());
            output.flush(); return bytes.toByteArray();
        } catch (IOException hidden) { throw unavailable(); }
    }
    private static ProfileView decodeResponse(UUID account, byte[] plain, boolean replay) {
        try {
            var input = new DataInputStream(new ByteArrayInputStream(plain));
            long version = input.readLong();
            String name = input.readBoolean() ? input.readUTF() : null;
            Instant updated = Instant.parse(input.readUTF()); Instant server = Instant.parse(input.readUTF());
            if (version < 2 || version > 9007199254740991L || input.available() != 0
                    || (name != null && !name.equals(ProfileName.normalize(name)))) throw unavailable();
            return new ProfileView(account, version, name, updated, server, replay);
        } catch (IOException | IllegalArgumentException hidden) { throw unavailable(); }
    }
    private static ProfileView unwrap(Outcome outcome) {
        if (outcome.refusal != null) throw new ProfileException(outcome.refusal);
        return outcome.view;
    }
    private static LocalDateTime sql(Instant value) { return LocalDateTime.ofInstant(value, ZoneOffset.UTC); }
    private static Instant instant(LocalDateTime value) { return value.toInstant(ZoneOffset.UTC); }
    private static ProfileException unavailable() { return new ProfileException(ProfileException.Code.SERVICE_UNAVAILABLE); }
    @Override public String toString() { return "PersistentProfileService[REDACTED]"; }
}
