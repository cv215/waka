package com.waka.mobility.voyage;

import com.waka.drivers.DriverAccessService;
import com.waka.identity.admission.PersistentDriverApplicationService;
import com.waka.identity.profile.ProfileCrypto;
import com.waka.identity.session.PersistentSessionService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * R11 intercity listings: an approved driver with effective access (no subscription needed) publishes a
 * voyage with 2 to 6 stops and a seat price for every pair of stops; authenticated clients browse the
 * upcoming ones without seeing who the driver is; the driver may cancel. Reservations and payments are
 * not part of this service yet. Not assembled by default.
 */
public final class PersistentVoyageService {
    /** Technical bounds, adjustable: published 1 h to 60 days ahead, at most 48 h from first to last stop. */
    public static final Duration MIN_NOTICE = Duration.ofHours(1), MAX_ADVANCE = Duration.ofDays(60), MAX_LENGTH = Duration.ofHours(48);
    public record Stop(String place, Instant plannedAt) { }
    public record VoyageView(UUID voyageId, String category, int seats, String state, List<Stop> stops,
            Map<String, Integer> prices, Instant createdAt, boolean idempotentReplay) { }
    private record Out<T>(T value, VoyageException.Code refusal) { }

    private final PersistentSessionService sessions;
    private final JdbcTemplate jdbc;
    private final PersistentDriverApplicationService applications;
    private final ProfileCrypto crypto;
    private final String gate;

    public PersistentVoyageService(PersistentSessionService sessions, JdbcTemplate jdbc,
            PersistentDriverApplicationService applications, ProfileCrypto crypto, Clock clock) {
        this.sessions = Objects.requireNonNull(sessions);
        this.jdbc = Objects.requireNonNull(jdbc);
        this.applications = Objects.requireNonNull(applications);
        this.crypto = Objects.requireNonNull(crypto);
        if (!sessions.usesDataSource(jdbc.getDataSource()) || !sessions.usesClock(Objects.requireNonNull(clock))) {
            throw new IllegalArgumentException("VOYAGE_DEPENDENCIES_MISMATCH");
        }
        if (crypto.reusesSessionKey(sessions)) throw new IllegalArgumentException("VOYAGE_SESSION_KEY_REUSE");
        gate = sessions.identityGateHash();
    }

    /** Same key and same listing: the same voyage; same key with another listing: refused. */
    public VoyageView publish(char[] token, String client, UUID key, List<Stop> stopsInput, int seats, Map<String, Integer> prices) {
        if (key == null || stopsInput == null || seats < 1 || seats > VoyageRules.MAX_SEATS) throw invalid();
        List<Stop> stops = new ArrayList<>();
        for (Stop stop : stopsInput) {
            if (stop == null || stop.plannedAt == null) throw invalid();
            stops.add(new Stop(place(stop.place), stop.plannedAt.truncatedTo(ChronoUnit.SECONDS)));
        }
        if (stops.size() < VoyageRules.MIN_STOPS || stops.size() > VoyageRules.MAX_STOPS || !VoyageRules.validPrices(stops.size(), prices)) {
            throw invalid();
        }
        for (int i = 1; i < stops.size(); i++) {
            if (!stops.get(i).plannedAt.isAfter(stops.get(i - 1).plannedAt)) throw invalid();
        }
        if (Duration.between(stops.getFirst().plannedAt, stops.getLast().plannedAt).compareTo(MAX_LENGTH) > 0) throw invalid();
        String canonical = stops.stream().map(s -> s.place + "@" + s.plannedAt.getEpochSecond()).collect(Collectors.joining("|"))
                + "#" + seats + "#" + VoyageRules.pairs(stops.size()).stream().map(p -> p + "=" + prices.get(p)).collect(Collectors.joining(","));
        return unwrap(sessions.withAuthenticatedSession(token, client, session -> {
            String actor = session.accountId().toString();
            String request = crypto.requestMac(actor, "voyage-publish-v1", key.toString());
            String payload = crypto.requestMac(actor, "voyage-payload-v1", canonical);
            var previous = jdbc.query("SELECT id,payload_mac FROM voyage WHERE gate_hash=? AND request_mac=? FOR UPDATE",
                    (rs, n) -> new String[] { rs.getString(1), rs.getString(2) }, gate, request);
            if (!previous.isEmpty()) {
                if (!MessageDigest.isEqual(previous.getFirst()[1].getBytes(StandardCharsets.US_ASCII), payload.getBytes(StandardCharsets.US_ASCII))) {
                    return new Out<VoyageView>(null, VoyageException.Code.IDEMPOTENCY_KEY_REUSED);
                }
                return new Out<>(view(UUID.fromString(previous.getFirst()[0]), true), null);
            }
            Instant now = session.serverTime().truncatedTo(ChronoUnit.MICROS);
            Instant departure = stops.getFirst().plannedAt;
            if (departure.isBefore(now.plus(MIN_NOTICE)) || departure.isAfter(now.plus(MAX_ADVANCE))) {
                return new Out<VoyageView>(null, VoyageException.Code.INVALID_REQUEST);
            }
            var basis = applications.accessBasisLocked(session.accountId(), now);
            var access = DriverAccessService.evaluate(basis, now);
            if (!Set.of("active", "provisional").contains(access.access())) return new Out<VoyageView>(null, VoyageException.Code.DRIVER_NOT_ALLOWED);
            if (basis.offeredSeats() == null || seats > basis.offeredSeats()) return new Out<VoyageView>(null, VoyageException.Code.TOO_MANY_SEATS);
            UUID id = UUID.randomUUID();
            jdbc.update("INSERT INTO voyage (gate_hash,id,driver_account_id,application_id,category,seats,state,departure_at,request_mac,payload_mac,created_at) VALUES (?,?,?,?,?,?,'published',?,?,?,?)",
                    gate, id.toString(), actor, basis.applicationId().toString(), basis.category(), seats, sql(departure), request, payload, sql(now));
            for (int i = 0; i < stops.size(); i++) {
                jdbc.update("INSERT INTO voyage_stop (gate_hash,voyage_id,stop_index,place,planned_at) VALUES (?,?,?,?,?)",
                        gate, id.toString(), i, stops.get(i).place, sql(stops.get(i).plannedAt));
            }
            for (String pair : VoyageRules.pairs(stops.size())) {
                String[] ends = pair.split("-");
                jdbc.update("INSERT INTO voyage_price (gate_hash,voyage_id,from_index,to_index,seat_price_xaf) VALUES (?,?,?,?,?)",
                        gate, id.toString(), Integer.parseInt(ends[0]), Integer.parseInt(ends[1]), prices.get(pair));
            }
            return new Out<>(view(id, false), null);
        }));
    }

    /** Upcoming published voyages, earliest first; the driver's identity is never shown. */
    public List<VoyageView> upcoming(char[] token, String client) {
        return unwrap(sessions.withAuthenticatedSession(token, client, session -> new Out<>(
                jdbc.query("SELECT id FROM voyage WHERE gate_hash=? AND state='published' AND departure_at>? ORDER BY departure_at,id LIMIT 50",
                        (rs, n) -> UUID.fromString(rs.getString(1)), gate, sql(session.serverTime()))
                        .stream().map(id -> view(id, false)).toList(), null)));
    }

    /** The caller's own voyages, newest departure first. */
    public List<VoyageView> mine(char[] token, String client) {
        return unwrap(sessions.withAuthenticatedSession(token, client, session -> new Out<>(
                jdbc.query("SELECT id FROM voyage WHERE gate_hash=? AND driver_account_id=? ORDER BY departure_at DESC,id LIMIT 50",
                        (rs, n) -> UUID.fromString(rs.getString(1)), gate, session.accountId().toString())
                        .stream().map(id -> view(id, false)).toList(), null)));
    }

    /** The owner cancels; repeating it changes nothing. Another driver's voyage is indistinguishable from an unknown one. */
    public VoyageView cancel(char[] token, String client, UUID voyageId, String reasonCode) {
        if (voyageId == null || reasonCode == null || !reasonCode.matches("[A-Z][A-Z0-9_]{2,47}")) throw invalid();
        return unwrap(sessions.withAuthenticatedSession(token, client, session -> {
            var rows = jdbc.query("SELECT state FROM voyage WHERE gate_hash=? AND id=? AND driver_account_id=? FOR UPDATE",
                    (rs, n) -> rs.getString(1), gate, voyageId.toString(), session.accountId().toString());
            if (rows.isEmpty()) return new Out<VoyageView>(null, VoyageException.Code.NOT_FOUND);
            if ("published".equals(rows.getFirst())) {
                jdbc.update("UPDATE voyage SET state='cancelled',cancel_reason=?,cancelled_at=? WHERE gate_hash=? AND id=?",
                        reasonCode, sql(session.serverTime()), gate, voyageId.toString());
            }
            return new Out<>(view(voyageId, false), null);
        }));
    }

    private VoyageView view(UUID id, boolean replay) {
        var row = jdbc.queryForMap("SELECT category,seats,state,created_at FROM voyage WHERE gate_hash=? AND id=?", gate, id.toString());
        List<Stop> stops = jdbc.query("SELECT place,planned_at FROM voyage_stop WHERE gate_hash=? AND voyage_id=? ORDER BY stop_index",
                (rs, n) -> new Stop(rs.getString(1), instant(rs.getObject(2, LocalDateTime.class))), gate, id.toString());
        Map<String, Integer> prices = new LinkedHashMap<>();
        jdbc.query("SELECT from_index,to_index,seat_price_xaf FROM voyage_price WHERE gate_hash=? AND voyage_id=? ORDER BY from_index,to_index",
                rs -> { prices.put(rs.getInt(1) + "-" + rs.getInt(2), rs.getInt(3)); }, gate, id.toString());
        return new VoyageView(id, (String) row.get("category"), ((Number) row.get("seats")).intValue(), (String) row.get("state"),
                List.copyOf(stops), Map.copyOf(prices), instant((LocalDateTime) row.get("created_at")), replay);
    }

    /** A place name: NFC, trimmed, 2 to 60 characters, no control or invisible character. */
    static String place(String input) {
        if (input == null) throw invalid();
        String text = Normalizer.normalize(input, Normalizer.Form.NFC).strip().replaceAll("\\s+", " ");
        int count = text.codePointCount(0, text.length());
        if (count < 2 || count > 60) throw invalid();
        for (int i = 0; i < text.length();) {
            int point = text.codePointAt(i);
            int type = Character.getType(point);
            if (type == Character.CONTROL || type == Character.FORMAT || type == Character.SURROGATE
                    || type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR) throw invalid();
            i += Character.charCount(point);
        }
        return text;
    }
    private static VoyageException invalid() { return new VoyageException(VoyageException.Code.INVALID_REQUEST); }
    private static <T> T unwrap(Out<T> out) {
        if (out.refusal != null) throw new VoyageException(out.refusal);
        return out.value;
    }
    private static LocalDateTime sql(Instant value) { return LocalDateTime.ofInstant(value, ZoneOffset.UTC); }
    private static Instant instant(LocalDateTime value) { return value.toInstant(ZoneOffset.UTC); }
    @Override public String toString() { return "PersistentVoyageService[REDACTED]"; }
}
