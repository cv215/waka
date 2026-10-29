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
            Map<String, Integer> prices, Map<String, Integer> availableSeats, Instant createdAt, boolean idempotentReplay) { }
    /**
     * A seat reservation on one section. {@code state} is awaiting_payment, expired (payment window over),
     * confirmed, boarded, cancelled_by_client or cancelled_by_driver; the settlement amounts are set on a
     * cancellation of a paid reservation and on boarding.
     */
    public record ReservationView(UUID reservationId, UUID voyageId, int fromIndex, int toIndex, int seats, int seatPriceXaf,
            int totalXaf, String state, Instant holdExpiresAt, Instant boardingAt, Integer refundXaf, Integer driverXaf,
            Integer wakaXaf, Integer feePercent, boolean idempotentReplay) { }
    /** R10 payment window, reused for voyages until a payment provider exists. */
    public static final Duration PAYMENT_WINDOW = Duration.ofMinutes(3);
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
                return new Out<>(view(UUID.fromString(previous.getFirst()[0]), true, session.serverTime()), null);
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
            return new Out<>(view(id, false, now), null);
        }));
    }

    /** Upcoming published voyages, earliest first; the driver's identity is never shown. */
    public List<VoyageView> upcoming(char[] token, String client) {
        return unwrap(sessions.withAuthenticatedSession(token, client, session -> new Out<>(
                jdbc.query("SELECT id FROM voyage WHERE gate_hash=? AND state='published' AND departure_at>? ORDER BY departure_at,id LIMIT 50",
                        (rs, n) -> UUID.fromString(rs.getString(1)), gate, sql(session.serverTime()))
                        .stream().map(id -> view(id, false, session.serverTime())).toList(), null)));
    }

    /** The caller's own voyages, newest departure first. */
    public List<VoyageView> mine(char[] token, String client) {
        return unwrap(sessions.withAuthenticatedSession(token, client, session -> new Out<>(
                jdbc.query("SELECT id FROM voyage WHERE gate_hash=? AND driver_account_id=? ORDER BY departure_at DESC,id LIMIT 50",
                        (rs, n) -> UUID.fromString(rs.getString(1)), gate, session.accountId().toString())
                        .stream().map(id -> view(id, false, session.serverTime())).toList(), null)));
    }

    /** The owner cancels; repeating it changes nothing. Another driver's voyage is indistinguishable from an unknown one. */
    public VoyageView cancel(char[] token, String client, UUID voyageId, String reasonCode) {
        if (voyageId == null || reasonCode == null || !reasonCode.matches("[A-Z][A-Z0-9_]{2,47}")) throw invalid();
        return unwrap(sessions.withAuthenticatedSession(token, client, session -> {
            var rows = jdbc.query("SELECT state FROM voyage WHERE gate_hash=? AND id=? AND driver_account_id=? FOR UPDATE",
                    (rs, n) -> rs.getString(1), gate, voyageId.toString(), session.accountId().toString());
            if (rows.isEmpty()) return new Out<VoyageView>(null, VoyageException.Code.NOT_FOUND);
            Instant now = session.serverTime();
            if ("published".equals(rows.getFirst())) {
                jdbc.update("UPDATE voyage SET state='cancelled',cancel_reason=?,cancelled_at=? WHERE gate_hash=? AND id=?",
                        reasonCode, sql(now), gate, voyageId.toString());
                // R11: every passenger is refunded in full; a reservation never paid simply ends.
                for (var r : reservations("voyage_id", voyageId.toString())) {
                    if (!r.active(now)) continue;
                    var settlement = "confirmed".equals(r.state) ? VoyageRules.cancelledByDriver(r.total) : null;
                    end(r.id, "cancelled_by_driver", now, settlement);
                }
            }
            return new Out<>(view(voyageId, false, now), null);
        }));
    }

    /**
     * Reserves {@code seats} from stop {@code fromIndex} to stop {@code toIndex}. Seats are counted per leg,
     * so passengers on separate sections can share a seat. Never on one's own voyage, never after the
     * boarding stop's planned time. Same key and same request: the same reservation.
     */
    public ReservationView reserve(char[] token, String client, UUID key, UUID voyageId, int fromIndex, int toIndex, int seats) {
        if (key == null || voyageId == null || fromIndex < 0 || toIndex <= fromIndex || toIndex >= VoyageRules.MAX_STOPS
                || seats < 1 || seats > VoyageRules.MAX_SEATS) throw invalid();
        String canonical = voyageId + "|" + fromIndex + "|" + toIndex + "|" + seats;
        return unwrap(sessions.withAuthenticatedSession(token, client, session -> {
            String actor = session.accountId().toString();
            String request = crypto.requestMac(actor, "voyage-reserve-v1", key.toString());
            String payload = crypto.requestMac(actor, "voyage-reserve-payload-v1", canonical);
            Instant now = session.serverTime().truncatedTo(ChronoUnit.MICROS);
            var previous = jdbc.query("SELECT id,payload_mac FROM voyage_reservation WHERE gate_hash=? AND request_mac=? FOR UPDATE",
                    (rs, n) -> new String[] { rs.getString(1), rs.getString(2) }, gate, request);
            if (!previous.isEmpty()) {
                if (!MessageDigest.isEqual(previous.getFirst()[1].getBytes(StandardCharsets.US_ASCII), payload.getBytes(StandardCharsets.US_ASCII))) {
                    return new Out<ReservationView>(null, VoyageException.Code.IDEMPOTENCY_KEY_REUSED);
                }
                return new Out<>(reservationView(reservations("id", previous.getFirst()[0]).getFirst(), now, true), null);
            }
            // The voyage row is the lock: two clients never take the last seat of a leg together.
            var voyage = jdbc.query("SELECT driver_account_id,seats,state FROM voyage WHERE gate_hash=? AND id=? FOR UPDATE",
                    (rs, n) -> new Object[] { rs.getString(1), rs.getInt(2), rs.getString(3) }, gate, voyageId.toString());
            if (voyage.isEmpty() || !"published".equals(voyage.getFirst()[2])) return new Out<ReservationView>(null, VoyageException.Code.NOT_FOUND);
            if (actor.equals(voyage.getFirst()[0])) return new Out<ReservationView>(null, VoyageException.Code.OWN_VOYAGE);
            var price = jdbc.query("SELECT p.seat_price_xaf,s.planned_at FROM voyage_price p JOIN voyage_stop s ON s.gate_hash=p.gate_hash "
                    + "AND s.voyage_id=p.voyage_id AND s.stop_index=p.from_index WHERE p.gate_hash=? AND p.voyage_id=? AND p.from_index=? AND p.to_index=?",
                    (rs, n) -> new Object[] { rs.getInt(1), instant(rs.getObject(2, LocalDateTime.class)) }, gate, voyageId.toString(), fromIndex, toIndex);
            if (price.isEmpty()) return new Out<ReservationView>(null, VoyageException.Code.INVALID_REQUEST);
            if (!now.isBefore((Instant) price.getFirst()[1])) return new Out<ReservationView>(null, VoyageException.Code.DEPARTED);
            int[] used = legUse(voyageId, now);
            int capacity = (Integer) voyage.getFirst()[1];
            for (int leg = fromIndex; leg < toIndex; leg++) {
                if (used[leg] + seats > capacity) return new Out<ReservationView>(null, VoyageException.Code.NOT_ENOUGH_SEATS);
            }
            int seatPrice = (Integer) price.getFirst()[0];
            UUID id = UUID.randomUUID();
            jdbc.update("INSERT INTO voyage_reservation (gate_hash,id,voyage_id,client_account_id,from_index,to_index,seats,seat_price_xaf,total_xaf,state,request_mac,payload_mac,created_at,hold_expires_at) VALUES (?,?,?,?,?,?,?,?,?,'awaiting_payment',?,?,?,?)",
                    gate, id.toString(), voyageId.toString(), actor, fromIndex, toIndex, seats, seatPrice, seatPrice * seats,
                    request, payload, sql(now), sql(now.plus(PAYMENT_WINDOW)));
            return new Out<>(reservationView(reservations("id", id.toString()).getFirst(), now, false), null);
        }));
    }

    /** The caller's own reservations, newest first. */
    public List<ReservationView> myReservations(char[] token, String client) {
        return unwrap(sessions.withAuthenticatedSession(token, client, session -> new Out<>(
                reservations("client_account_id", session.accountId().toString()).stream()
                        .map(r -> reservationView(r, session.serverTime(), false)).toList(), null)));
    }

    /**
     * The client cancels before boarding. A paid reservation is settled with the R11 tiers (by the planned
     * time of the client's boarding stop); an unpaid one simply ends. Repeating it changes nothing.
     */
    public ReservationView cancelReservation(char[] token, String client, UUID reservationId) {
        if (reservationId == null) throw invalid();
        return unwrap(sessions.withAuthenticatedSession(token, client, session -> {
            Instant now = session.serverTime();
            var rows = reservations("id", reservationId.toString()).stream()
                    .filter(r -> r.client.equals(session.accountId().toString())).toList();
            if (rows.isEmpty()) return new Out<ReservationView>(null, VoyageException.Code.NOT_FOUND);
            jdbc.query("SELECT id FROM voyage WHERE gate_hash=? AND id=? FOR UPDATE", (rs, n) -> rs.getString(1), gate, rows.getFirst().voyage);
            Reservation r = reservations("id", reservationId.toString()).getFirst();
            if ("boarded".equals(r.state)) return new Out<ReservationView>(null, VoyageException.Code.ALREADY_BOARDED);
            if (r.active(now)) {
                var settlement = "confirmed".equals(r.state) ? VoyageRules.cancelledByClient(r.total, r.boardingAt, now) : null;
                end(r.id, "cancelled_by_client", now, settlement);
            }
            return new Out<>(reservationView(reservations("id", reservationId.toString()).getFirst(), now, false), null);
        }));
    }

    private record Reservation(String id, String voyage, String client, int from, int to, int seats, int seatPrice, int total,
            String state, Instant holdExpiresAt, Instant boardingAt, Integer refund, Integer driver, Integer waka, Integer fee) {
        /** Holds seats: paid, or still within its payment window. */
        boolean active(Instant now) {
            return "confirmed".equals(state) || ("awaiting_payment".equals(state) && now.isBefore(holdExpiresAt));
        }
    }
    private List<Reservation> reservations(String column, String value) {
        return jdbc.query("SELECT r.*,s.planned_at FROM voyage_reservation r JOIN voyage_stop s ON s.gate_hash=r.gate_hash AND s.voyage_id=r.voyage_id "
                + "AND s.stop_index=r.from_index WHERE r.gate_hash=? AND r." + column + "=? ORDER BY r.created_at DESC,r.id LIMIT 100",
                (rs, n) -> new Reservation(rs.getString("id"), rs.getString("voyage_id"), rs.getString("client_account_id"),
                        rs.getInt("from_index"), rs.getInt("to_index"), rs.getInt("seats"), rs.getInt("seat_price_xaf"), rs.getInt("total_xaf"),
                        rs.getString("state"), instant(rs.getObject("hold_expires_at", LocalDateTime.class)),
                        instant(rs.getObject("planned_at", LocalDateTime.class)), (Integer) rs.getObject("refund_xaf"),
                        (Integer) rs.getObject("driver_xaf"), (Integer) rs.getObject("waka_xaf"), (Integer) rs.getObject("fee_percent")),
                gate, value);
    }
    private void end(String id, String state, Instant now, VoyageRules.Settlement settlement) {
        jdbc.update("UPDATE voyage_reservation SET state=?,ended_at=?,refund_xaf=?,driver_xaf=?,waka_xaf=?,fee_percent=? WHERE gate_hash=? AND id=?",
                state, sql(now), settlement == null ? null : settlement.refundXaf(), settlement == null ? null : settlement.driverXaf(),
                settlement == null ? null : settlement.wakaXaf(), settlement == null ? null : settlement.feePercent(), gate, id);
    }
    private ReservationView reservationView(Reservation r, Instant now, boolean replay) {
        String state = "awaiting_payment".equals(r.state) && !now.isBefore(r.holdExpiresAt) ? "expired" : r.state;
        return new ReservationView(UUID.fromString(r.id), UUID.fromString(r.voyage), r.from, r.to, r.seats, r.seatPrice, r.total,
                state, r.holdExpiresAt, r.boardingAt, r.refund, r.driver, r.waka, r.fee, replay);
    }
    /** Seats held on each leg k (from stop k to stop k+1). */
    private int[] legUse(UUID voyageId, Instant now) {
        int[] used = new int[VoyageRules.MAX_STOPS];
        for (var r : reservations("voyage_id", voyageId.toString())) {
            if (!r.active(now)) continue;
            for (int leg = r.from; leg < r.to; leg++) used[leg] += r.seats;
        }
        return used;
    }

    private VoyageView view(UUID id, boolean replay, Instant now) {
        var row = jdbc.queryForMap("SELECT category,seats,state,created_at FROM voyage WHERE gate_hash=? AND id=?", gate, id.toString());
        List<Stop> stops = jdbc.query("SELECT place,planned_at FROM voyage_stop WHERE gate_hash=? AND voyage_id=? ORDER BY stop_index",
                (rs, n) -> new Stop(rs.getString(1), instant(rs.getObject(2, LocalDateTime.class))), gate, id.toString());
        Map<String, Integer> prices = new LinkedHashMap<>();
        jdbc.query("SELECT from_index,to_index,seat_price_xaf FROM voyage_price WHERE gate_hash=? AND voyage_id=? ORDER BY from_index,to_index",
                rs -> { prices.put(rs.getInt(1) + "-" + rs.getInt(2), rs.getInt(3)); }, gate, id.toString());
        int seats = ((Number) row.get("seats")).intValue();
        int[] used = legUse(id, now);
        Map<String, Integer> available = new LinkedHashMap<>();
        for (String pair : prices.keySet()) {
            String[] ends = pair.split("-");
            int max = 0;
            for (int leg = Integer.parseInt(ends[0]); leg < Integer.parseInt(ends[1]); leg++) max = Math.max(max, used[leg]);
            available.put(pair, Math.max(0, seats - max));
        }
        return new VoyageView(id, (String) row.get("category"), seats, (String) row.get("state"),
                List.copyOf(stops), Map.copyOf(prices), Map.copyOf(available), instant((LocalDateTime) row.get("created_at")), replay);
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
