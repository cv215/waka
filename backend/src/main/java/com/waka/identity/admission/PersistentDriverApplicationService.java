package com.waka.identity.admission;

import com.waka.identity.session.PersistentSessionService;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A26-A28 core: the owner's draft candidature and declared vehicle. Never an admission:
 * files, submission, decisions and access belong to later points. Construct explicitly
 * after qualifying keys and maintenance policy; the ordinary application does not assemble it.
 */
public final class PersistentDriverApplicationService {
    public record ApplicationView(UUID applicationId, UUID accountId, String requestedCategory, String state,
            long version, VehicleFields vehicleFields, List<String> missingVehicleFields, Instant createdAt,
            Instant updatedAt, Instant serverTime, boolean idempotentReplay) {
        @Override public String toString() { return "ApplicationView[REDACTED]"; }
    }
    private record Row(UUID id, String category, String state, long version, String keyId, byte[] cipher,
            Instant createdAt, Instant updatedAt) {
        @Override public String toString() { return "ApplicationRow[REDACTED]"; }
    }
    private record CommandRow(String payloadMac, String keyId, byte[] cipher, Instant createdAt, Instant expiresAt) {
        @Override public String toString() { return "ApplicationCommandRow[REDACTED]"; }
    }
    private record Outcome(ApplicationView view, AdmissionException.Code refusal) { }
    private static final String DRAFT = "draft";
    private static final long MAX_VERSION = 9007199254740991L;
    private final PersistentSessionService sessions;
    private final JdbcTemplate jdbc;
    private final AdmissionCrypto crypto;
    private final Duration responseLifetime;
    private final String gate;
    private final TransactionTemplate maintenance;
    private final Clock clock;

    public PersistentDriverApplicationService(PersistentSessionService sessions, JdbcTemplate jdbc,
            DataSourceTransactionManager manager, AdmissionCrypto crypto, Duration responseLifetime, Clock clock) {
        this.sessions = Objects.requireNonNull(sessions);
        this.jdbc = Objects.requireNonNull(jdbc);
        this.crypto = Objects.requireNonNull(crypto);
        this.clock = Objects.requireNonNull(clock);
        if (jdbc.getDataSource() == null || jdbc.getDataSource() != Objects.requireNonNull(manager).getDataSource()
                || !sessions.usesDataSource(jdbc.getDataSource()) || !sessions.usesClock(clock))
            throw new IllegalArgumentException("ADMISSION_DEPENDENCIES_MISMATCH");
        if (crypto.reusesSessionKey(sessions)) throw new IllegalArgumentException("ADMISSION_SESSION_KEY_REUSE");
        if (responseLifetime == null || responseLifetime.isNegative() || responseLifetime.isZero()
                || responseLifetime.getNano() % 1000 != 0 || responseLifetime.compareTo(Duration.ofDays(365)) > 0) {
            throw new IllegalArgumentException("ADMISSION_RESPONSE_LIFETIME_REQUIRED");
        }
        this.responseLifetime = responseLifetime;
        this.gate = sessions.identityGateHash();
        maintenance = new TransactionTemplate(manager);
        maintenance.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        maintenance.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** A26: a declared category creates a draft only; one open candidature per account. */
    public ApplicationView create(char[] token, String client, UUID key, String requestedCategory) {
        if (key == null || !("taxi".equals(requestedCategory) || "private".equals(requestedCategory))) throw invalid();
        return unwrap(sessions.withAuthenticatedSession(token, client, session -> {
            UUID account = session.accountId();
            String actor = account.toString();
            String request = crypto.mac("admission-request-v1", actor, "POST /api/v1/driver-applications", key.toString());
            String payload = crypto.mac("admission-create-v1", actor, requestedCategory);
            Instant now = session.serverTime();
            Outcome replay = replay(account, request, payload, now);
            if (replay != null) return replay;
            if (openApplication(account) != null) return new Outcome(null, AdmissionException.Code.APPLICATION_ALREADY_OPEN);
            UUID id = UUID.randomUUID();
            byte[] vehicle = encryptVehicle(actor, id, 1, now, VehicleFields.EMPTY);
            try {
                jdbc.update("INSERT INTO driver_application (gate_hash,id,account_id,requested_category,state,open_marker,version,key_id,vehicle_cipher,created_at,updated_at) VALUES (?,?,?,?,?,1,1,?,?,?,?)",
                        gate, id.toString(), actor, requestedCategory, DRAFT, crypto.keyId(), vehicle, sql(now), sql(now));
            } finally { Arrays.fill(vehicle, (byte) 0); }
            ApplicationView view = view(id, account, requestedCategory, DRAFT, 1, VehicleFields.EMPTY, now, now, now, false);
            remember(actor, request, payload, view, now);
            return new Outcome(view, null);
        }));
    }

    /** Finds the caller's own open candidature without knowing its identifier. */
    public ApplicationView readCurrent(char[] token, String client) {
        return unwrap(sessions.withAuthenticatedSession(token, client, session -> {
            Row row = openApplication(session.accountId());
            if (row == null) return new Outcome(null, AdmissionException.Code.NOT_FOUND);
            return new Outcome(current(session.accountId(), row, session.serverTime()), null);
        }));
    }

    /** A27: owner only; another candidate's identifier is indistinguishable from an unknown one. */
    public ApplicationView read(char[] token, String client, UUID applicationId) {
        if (applicationId == null) throw invalid();
        return unwrap(sessions.withAuthenticatedSession(token, client, session -> {
            Row row = owned(session.accountId(), applicationId);
            if (row == null) return new Outcome(null, AdmissionException.Code.NOT_FOUND);
            return new Outcome(current(session.accountId(), row, session.serverTime()), null);
        }));
    }

    /** A28 limited to the eight declared vehicle fields; the whole object replaces the previous one. */
    public ApplicationView updateVehicle(char[] token, String client, UUID applicationId, UUID key,
            long expectedVersion, VehicleFields input) {
        if (applicationId == null || key == null || input == null || expectedVersion < 1 || expectedVersion > MAX_VERSION) {
            throw invalid();
        }
        // Validated before the session transaction, whose wrapper reports any inner error as unavailable.
        VehicleFields fields = VehicleFields.declared(input.brand(), input.model(), input.year(), input.color(),
                input.registration(), input.authorizedTotalCapacity(), input.offeredPassengerSeats(),
                input.usageRight(), clock.instant().atZone(ZoneOffset.UTC).getYear());
        return unwrap(sessions.withAuthenticatedSession(token, client, session -> {
            UUID account = session.accountId();
            String actor = account.toString();
            Instant now = session.serverTime();
            String request = crypto.mac("admission-request-v1", actor,
                    "PATCH /api/v1/driver-applications/" + applicationId, key.toString());
            byte[] encoded = encodeVehicle(fields);
            String payload;
            try { payload = crypto.mac("admission-vehicle-v1", actor, applicationId.toString(),
                    Long.toString(expectedVersion), HexFormat.of().formatHex(encoded)); }
            finally { Arrays.fill(encoded, (byte) 0); }
            Outcome replay = replay(account, request, payload, now);
            if (replay != null) return replay;
            Row row = owned(account, applicationId);
            if (row == null) return new Outcome(null, AdmissionException.Code.NOT_FOUND);
            if (!DRAFT.equals(row.state)) return new Outcome(null, AdmissionException.Code.VERSION_CONFLICT);
            if (expectedVersion != row.version || row.version == MAX_VERSION) {
                return new Outcome(null, AdmissionException.Code.VERSION_CONFLICT);
            }
            long next = row.version + 1;
            byte[] vehicle = encryptVehicle(actor, applicationId, next, now, fields);
            try {
                int changed = jdbc.update("UPDATE driver_application SET version=?,key_id=?,vehicle_cipher=?,updated_at=? WHERE gate_hash=? AND id=? AND account_id=? AND version=?",
                        next, crypto.keyId(), vehicle, sql(now), gate, applicationId.toString(), actor, row.version);
                if (changed != 1) return new Outcome(null, AdmissionException.Code.VERSION_CONFLICT);
            } finally { Arrays.fill(vehicle, (byte) 0); }
            ApplicationView view = view(applicationId, account, row.category, row.state, next, fields, row.createdAt, now, now, false);
            remember(actor, request, payload, view, now);
            return new Outcome(view, null);
        }));
    }

    /** Explicitly scheduled by the runtime owner; retains request markers so nothing is reapplied. */
    public int eraseExpiredResponseSecrets() {
        return maintenance.execute(status -> jdbc.update(
                "UPDATE driver_application_command SET response_cipher=NULL WHERE gate_hash=? AND response_cipher IS NOT NULL AND expires_at<=?",
                gate, sql(clock.instant())));
    }

    private Outcome replay(UUID account, String request, String payload, Instant now) {
        var rows = jdbc.query("SELECT payload_mac,key_id,response_cipher,created_at,expires_at FROM driver_application_command WHERE gate_hash=? AND request_mac=? FOR UPDATE",
                (rs, n) -> new CommandRow(rs.getString("payload_mac"), rs.getString("key_id"), rs.getBytes("response_cipher"),
                        instant(rs.getObject("created_at", LocalDateTime.class)), instant(rs.getObject("expires_at", LocalDateTime.class))),
                gate, request);
        if (rows.isEmpty()) return null;
        CommandRow previous = rows.getFirst();
        if (!AdmissionCrypto.equal(previous.payloadMac, payload)) return new Outcome(null, AdmissionException.Code.IDEMPOTENCY_KEY_REUSED);
        if (previous.cipher == null || !now.isBefore(previous.expiresAt)) {
            jdbc.update("UPDATE driver_application_command SET response_cipher=NULL WHERE gate_hash=? AND request_mac=? AND response_cipher IS NOT NULL",
                    gate, request);
            return new Outcome(null, AdmissionException.Code.REPLAY_EXPIRED);
        }
        if (!crypto.keyId().equals(previous.keyId)) throw unavailable();
        byte[] plain = crypto.decrypt(previous.cipher, commandAad(account.toString(), request, payload, previous));
        try { return new Outcome(decodeResponse(account, plain), null); }
        finally { Arrays.fill(plain, (byte) 0); }
    }

    private void remember(String actor, String request, String payload, ApplicationView view, Instant now) {
        Instant expires = now.plus(responseLifetime);
        CommandRow created = new CommandRow(payload, crypto.keyId(), null, now, expires);
        byte[] plain = encodeResponse(view);
        byte[] cipher;
        try { cipher = crypto.encrypt(plain, commandAad(actor, request, payload, created)); }
        finally { Arrays.fill(plain, (byte) 0); }
        try {
            jdbc.update("INSERT INTO driver_application_command (request_mac,gate_hash,account_id,payload_mac,key_id,response_cipher,created_at,expires_at) VALUES (?,?,?,?,?,?,?,?)",
                    request, gate, actor, payload, crypto.keyId(), cipher, sql(now), sql(expires));
        } finally { Arrays.fill(cipher, (byte) 0); }
    }

    private Row openApplication(UUID account) {
        return single(jdbc.query("SELECT id,requested_category,state,version,key_id,vehicle_cipher,created_at,updated_at FROM driver_application WHERE gate_hash=? AND account_id=? AND open_marker=1 FOR UPDATE",
                (rs, n) -> row(rs), gate, account.toString()));
    }
    private Row owned(UUID account, UUID id) {
        return single(jdbc.query("SELECT id,requested_category,state,version,key_id,vehicle_cipher,created_at,updated_at FROM driver_application WHERE gate_hash=? AND id=? AND account_id=? FOR UPDATE",
                (rs, n) -> row(rs), gate, id.toString(), account.toString()));
    }
    private static Row row(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Row(UUID.fromString(rs.getString("id")), rs.getString("requested_category"), rs.getString("state"),
                rs.getLong("version"), rs.getString("key_id"), rs.getBytes("vehicle_cipher"),
                instant(rs.getObject("created_at", LocalDateTime.class)), instant(rs.getObject("updated_at", LocalDateTime.class)));
    }
    private static Row single(List<Row> rows) {
        if (rows.size() > 1) throw unavailable();
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private ApplicationView current(UUID account, Row row, Instant serverTime) {
        if (!crypto.keyId().equals(row.keyId)) throw unavailable();
        byte[] plain = crypto.decrypt(row.cipher, vehicleAad(account.toString(), row.id, row.version, row.updatedAt, row.keyId));
        try {
            VehicleFields fields = VehicleFields.read(new DataInputStream(new ByteArrayInputStream(plain)));
            return view(row.id, account, row.category, row.state, row.version, fields, row.createdAt, row.updatedAt, serverTime, false);
        } catch (IOException hidden) { throw unavailable(); }
        finally { Arrays.fill(plain, (byte) 0); }
    }
    private byte[] encryptVehicle(String actor, UUID id, long version, Instant updated, VehicleFields fields) {
        byte[] plain = encodeVehicle(fields);
        try { return crypto.encrypt(plain, vehicleAad(actor, id, version, updated, crypto.keyId())); }
        finally { Arrays.fill(plain, (byte) 0); }
    }
    private static byte[] encodeVehicle(VehicleFields fields) {
        try {
            var bytes = new ByteArrayOutputStream(); var output = new DataOutputStream(bytes);
            fields.write(output); output.flush(); return bytes.toByteArray();
        } catch (IOException hidden) { throw unavailable(); }
    }
    private byte[] vehicleAad(String actor, UUID id, long version, Instant updated, String keyId) {
        return AdmissionCrypto.aad("waka-admission-vehicle-v1", gate, actor, id.toString(), Long.toString(version),
                updated.toString(), keyId);
    }
    private byte[] commandAad(String actor, String request, String payload, CommandRow row) {
        return AdmissionCrypto.aad("waka-admission-response-v1", gate, actor, request, payload,
                row.keyId, row.createdAt.toString(), row.expiresAt.toString());
    }

    private static ApplicationView view(UUID id, UUID account, String category, String state, long version,
            VehicleFields fields, Instant created, Instant updated, Instant server, boolean replay) {
        return new ApplicationView(id, account, category, state, version, fields, fields.missing(), created, updated, server, replay);
    }
    private static byte[] encodeResponse(ApplicationView view) {
        try {
            var bytes = new ByteArrayOutputStream(); var output = new DataOutputStream(bytes);
            output.writeUTF(view.applicationId.toString()); output.writeUTF(view.requestedCategory);
            output.writeUTF(view.state); output.writeLong(view.version);
            view.vehicleFields.write(output);
            output.writeUTF(view.createdAt.toString()); output.writeUTF(view.updatedAt.toString());
            output.writeUTF(view.serverTime.toString());
            output.flush(); return bytes.toByteArray();
        } catch (IOException hidden) { throw unavailable(); }
    }
    private static ApplicationView decodeResponse(UUID account, byte[] plain) {
        try {
            var input = new DataInputStream(new ByteArrayInputStream(plain));
            UUID id = UUID.fromString(input.readUTF());
            String category = input.readUTF(), state = input.readUTF();
            long version = input.readLong();
            VehicleFields fields = VehicleFields.read(input);
            Instant created = Instant.parse(input.readUTF()), updated = Instant.parse(input.readUTF()),
                    server = Instant.parse(input.readUTF());
            if (input.available() != 0 || version < 1 || version > MAX_VERSION || !DRAFT.equals(state)
                    || !("taxi".equals(category) || "private".equals(category))) throw unavailable();
            return view(id, account, category, state, version, fields, created, updated, server, true);
        } catch (IOException | IllegalArgumentException hidden) { throw unavailable(); }
    }

    private static ApplicationView unwrap(Outcome outcome) {
        if (outcome.refusal != null) throw new AdmissionException(outcome.refusal);
        return outcome.view;
    }
    private static LocalDateTime sql(Instant value) { return LocalDateTime.ofInstant(value, ZoneOffset.UTC); }
    private static Instant instant(LocalDateTime value) { return value.toInstant(ZoneOffset.UTC); }
    private static AdmissionException invalid() { return new AdmissionException(AdmissionException.Code.INVALID_REQUEST); }
    private static AdmissionException unavailable() { return new AdmissionException(AdmissionException.Code.SERVICE_UNAVAILABLE); }
    @Override public String toString() { return "PersistentDriverApplicationService[REDACTED]"; }
}
