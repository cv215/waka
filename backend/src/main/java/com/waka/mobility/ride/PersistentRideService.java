package com.waka.mobility.ride;

import com.waka.drivers.DriverAccessService;
import com.waka.geo.GeoPoint;
import com.waka.geo.PickupTimeProvider;
import com.waka.geo.RouteDistanceProvider;
import com.waka.identity.admission.PersistentDriverApplicationService;
import com.waka.identity.profile.ProfileCrypto;
import com.waka.identity.session.PersistentSessionService;
import com.waka.mobility.quote.PersistentQuoteService;
import com.waka.pricing.domain.FareCalculation;
import com.waka.pricing.domain.PricingEngine;
import com.waka.pricing.infrastructure.JdbcFareScheduleRepository;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * R13 city rides: driver presence, a search on the client's quote, one proposal at a time to the
 * closest eligible driver by road time, acceptance creating the single active reservation of the car,
 * then arrival, boarding by code, end of ride and cancellations (R08: full refund before boarding).
 * Road times come from the maps provider, called outside transactions. No money moves here.
 * Not assembled by default.
 */
public final class PersistentRideService {
    public static final Duration SEARCH = Duration.ofMinutes(2), PROPOSAL = Duration.ofSeconds(30),
            POSITION_FRESHNESS = Duration.ofSeconds(60), PAYMENT_WINDOW = Duration.ofMinutes(3), WAIT = Duration.ofMinutes(5);
    public static final int MAX_PICKUP_SECONDS = 600;
    private static final Set<String> ACTIVE = Set.of("awaiting_payment", "confirmed", "driver_arrived", "boarded");
    /** States in which the client follows the driver coming to the pickup point. */
    private static final Set<String> COMING = Set.of("awaiting_payment", "confirmed", "driver_arrived");

    /**
     * {@code driverPosition}/{@code driverPositionAt}: for the client only, while the driver comes (from acceptance until
     * boarding), and only when the driver's position is less than a minute old; otherwise null.
     */
    public record RideView(UUID reservationId, String mode, int seats, long priceXaf, long driverXaf, String state,
            Instant holdExpiresAt, Instant arrivedAt, String viewerRole, String boardingCode, Long refundXaf,
            GeoPoint pickup, GeoPoint dropoff, GeoPoint driverPosition, Instant driverPositionAt) {
        @Override public String toString() { return "RideView[REDACTED]"; }
    }
    public record SearchView(UUID searchId, String state, Instant expiresAt, boolean proposalPending, RideView ride,
            boolean idempotentReplay) { }
    public record ProposalView(UUID proposalId, String mode, int seats, long priceXaf, long driverXaf, int pickupSeconds,
            Instant expiresAt, GeoPoint pickup, GeoPoint dropoff) {
        @Override public String toString() { return "ProposalView[REDACTED]"; }
    }
    public record PresenceView(boolean online, Instant positionAt) { }
    private record Out<T>(T value, RideException.Code refusal) { }
    private record Candidate(String driver, GeoPoint position) { }

    private final PersistentSessionService sessions;
    private final JdbcTemplate jdbc;
    private final PersistentDriverApplicationService applications;
    private final PersistentQuoteService quotes;
    private final JdbcFareScheduleRepository fares;
    private final PickupTimeProvider pickupTimes;
    private final ProfileCrypto crypto;
    private final PricingEngine engine = new PricingEngine();
    private final SecureRandom random = new SecureRandom();
    private final Clock clock;
    private final String gate;

    public PersistentRideService(PersistentSessionService sessions, JdbcTemplate jdbc, PersistentDriverApplicationService applications,
            PersistentQuoteService quotes, JdbcFareScheduleRepository fares, PickupTimeProvider pickupTimes, ProfileCrypto crypto, Clock clock) {
        this.sessions = Objects.requireNonNull(sessions);
        this.jdbc = Objects.requireNonNull(jdbc);
        this.applications = Objects.requireNonNull(applications);
        this.quotes = Objects.requireNonNull(quotes);
        this.fares = Objects.requireNonNull(fares);
        this.pickupTimes = Objects.requireNonNull(pickupTimes);
        this.crypto = Objects.requireNonNull(crypto);
        if (!sessions.usesDataSource(jdbc.getDataSource()) || !sessions.usesClock(Objects.requireNonNull(clock))) {
            throw new IllegalArgumentException("RIDE_DEPENDENCIES_MISMATCH");
        }
        if (crypto.reusesSessionKey(sessions)) throw new IllegalArgumentException("RIDE_SESSION_KEY_REUSE");
        this.clock = clock;
        gate = sessions.identityGateHash();
    }

    // ---- Driver presence (A17) ----

    /** Online with a position taken now by the server clock, or offline (the position is then erased). */
    public PresenceView presence(char[] token, String client, boolean online, GeoPoint position) {
        if (online && position == null) throw invalid();
        return unwrap(sessions.withAuthenticatedSession(token, client, session -> {
            Instant now = micros(session.serverTime());
            String actor = session.accountId().toString();
            if (online) {
                var access = DriverAccessService.evaluate(applications.accessBasisLocked(session.accountId(), now), now);
                if (!Set.of("active", "provisional").contains(access.access())) return new Out<PresenceView>(null, RideException.Code.DRIVER_NOT_ALLOWED);
            }
            byte[] cipher = online ? crypto.encrypt(position.canonical().getBytes(StandardCharsets.US_ASCII), positionAad(actor, crypto.keyId())) : null;
            jdbc.update("INSERT INTO driver_presence (gate_hash,account_id,online,key_id,position_cipher,position_at,updated_at) VALUES (?,?,?,?,?,?,?) "
                    + "ON DUPLICATE KEY UPDATE online=VALUES(online),key_id=VALUES(key_id),position_cipher=VALUES(position_cipher),position_at=VALUES(position_at),updated_at=VALUES(updated_at)",
                    gate, actor, online ? 1 : 0, crypto.keyId(), cipher, online ? sql(now) : null, sql(now));
            return new Out<>(new PresenceView(online, online ? now : null), null);
        }));
    }

    // ---- Client search (A09-A11) ----

    /** A09: a search on the client's own valid quote, two minutes within the quote validity. */
    public SearchView search(char[] token, String client, UUID key, UUID quoteId) {
        if (key == null || quoteId == null) throw invalid();
        SearchView created = unwrap(sessions.withAuthenticatedSession(token, client, session -> {
            String actor = session.accountId().toString();
            String request = crypto.requestMac(actor, "ride-search-v1", key + "|" + quoteId);
            var previous = jdbc.query("SELECT id FROM ride_search WHERE gate_hash=? AND (request_mac=? OR (quote_id=? AND client_account_id=?))",
                    (rs, n) -> rs.getString(1), gate, request, quoteId.toString(), actor);
            Instant now = micros(session.serverTime());
            if (!previous.isEmpty()) return new Out<>(searchView(previous.getFirst(), actor, now, true), null);
            var quote = quotes.forRideLocked(session.accountId(), quoteId);
            if (quote == null) return new Out<SearchView>(null, RideException.Code.NOT_FOUND);
            if (!now.isBefore(quote.expiresAt())) return new Out<SearchView>(null, RideException.Code.QUOTE_EXPIRED);
            Instant expires = earliest(now.plus(SEARCH), quote.expiresAt());
            String id = UUID.randomUUID().toString();
            jdbc.update("INSERT INTO ride_search (gate_hash,id,quote_id,client_account_id,state,request_mac,created_at,expires_at) VALUES (?,?,?,?,'searching',?,?,?)",
                    gate, id, quoteId.toString(), actor, request, sql(now), sql(expires));
            return new Out<>(searchView(id, actor, now, false), null);
        }));
        advance(token, client, created.searchId());
        return read(token, client, created.searchId(), created.idempotentReplay());
    }

    /** A10: the client's search; reading it also sends the next proposal when none is pending and time remains. */
    public SearchView readSearch(char[] token, String client, UUID searchId) {
        if (searchId == null) throw invalid();
        advance(token, client, searchId);
        return read(token, client, searchId, false);
    }

    /** A11: cancels a search without a winning acceptance; a matched search is cancelled through its ride. */
    public SearchView cancelSearch(char[] token, String client, UUID searchId) {
        if (searchId == null) throw invalid();
        return unwrap(sessions.withAuthenticatedSession(token, client, session -> {
            String actor = session.accountId().toString();
            var state = jdbc.query("SELECT state FROM ride_search WHERE gate_hash=? AND id=? AND client_account_id=? FOR UPDATE",
                    (rs, n) -> rs.getString(1), gate, searchId.toString(), actor);
            if (state.isEmpty()) return new Out<SearchView>(null, RideException.Code.NOT_FOUND);
            if ("searching".equals(state.getFirst())) {
                jdbc.update("UPDATE ride_search SET state='cancelled' WHERE gate_hash=? AND id=?", gate, searchId.toString());
            }
            return new Out<>(searchView(searchId.toString(), actor, micros(session.serverTime()), false), null);
        }));
    }

    private SearchView read(char[] token, String client, UUID searchId, boolean replay) {
        return unwrap(sessions.withAuthenticatedSession(token, client, session -> {
            String actor = session.accountId().toString();
            var owned = jdbc.query("SELECT id FROM ride_search WHERE gate_hash=? AND id=? AND client_account_id=?",
                    (rs, n) -> rs.getString(1), gate, searchId.toString(), actor);
            if (owned.isEmpty()) return new Out<SearchView>(null, RideException.Code.NOT_FOUND);
            return new Out<>(searchView(owned.getFirst(), actor, micros(session.serverTime()), replay), null);
        }));
    }

    /**
     * Sends the next proposal: candidates are collected in one transaction, road times are asked outside
     * any transaction, then the best candidate is rechecked and proposed in a second transaction.
     */
    private void advance(char[] token, String client, UUID searchId) {
        record Pending(GeoPoint pickup, List<Candidate> candidates, int seats, String mode) { }
        Pending pending = unwrap(sessions.withAuthenticatedSession(token, client, session -> {
            Instant now = micros(session.serverTime());
            expireHolds(now);
            var search = jdbc.query("SELECT quote_id,client_account_id,state,expires_at FROM ride_search WHERE gate_hash=? AND id=? AND client_account_id=?",
                    (rs, n) -> new Object[] { rs.getString(1), rs.getString(2), rs.getString(3), instant(rs.getObject(4, LocalDateTime.class)) },
                    gate, searchId.toString(), session.accountId().toString());
            if (search.isEmpty() || !"searching".equals(search.getFirst()[2]) || !now.isBefore((Instant) search.getFirst()[3]) || liveProposal(searchId, now)) {
                return new Out<Pending>(null, null);
            }
            var quote = quotes.forRideLocked(session.accountId(), UUID.fromString((String) search.getFirst()[0]));
            List<Candidate> candidates = new ArrayList<>();
            for (Candidate c : online(now)) {
                if (c.driver.equals(search.getFirst()[1]) || proposedBefore(searchId, c.driver) || hasActiveRide(c.driver)) continue;
                var basis = applications.accessBasisLocked(UUID.fromString(c.driver), now);
                var access = DriverAccessService.evaluate(basis, now);
                if (!Set.of("active", "provisional").contains(access.access()) || basis.offeredSeats() == null
                        || basis.offeredSeats() < quote.passengerCount()) continue;
                candidates.add(c);
            }
            return new Out<>(new Pending(quote.origin(), candidates, quote.passengerCount(), quote.mode()), null);
        }));
        if (pending == null || pending.candidates().isEmpty()) return;
        record Timed(String driver, int seconds) { }
        List<Timed> timed = new ArrayList<>();
        for (Candidate c : pending.candidates()) {
            try {
                Integer seconds = pickupTimes.pickupSeconds(c.position, pending.pickup());
                if (seconds != null && seconds >= 0 && seconds <= MAX_PICKUP_SECONDS) timed.add(new Timed(c.driver, seconds));
            } catch (RouteDistanceProvider.RouteUnavailableException | RuntimeException hidden) {
                // A driver without a reliable road time is simply not proposed this time.
            }
        }
        timed.sort(Comparator.comparingInt(Timed::seconds).thenComparing(Timed::driver));
        if (timed.isEmpty()) return;
        sessions.withAuthenticatedSession(token, client, session -> {
            Instant now = micros(session.serverTime());
            var search = jdbc.query("SELECT state,expires_at,quote_id FROM ride_search WHERE gate_hash=? AND id=? FOR UPDATE",
                    (rs, n) -> new Object[] { rs.getString(1), instant(rs.getObject(2, LocalDateTime.class)), rs.getString(3) }, gate, searchId.toString());
            if (search.isEmpty() || !"searching".equals(search.getFirst()[0]) || !now.isBefore((Instant) search.getFirst()[1]) || liveProposal(searchId, now)) {
                return Boolean.FALSE;
            }
            var quote = quotes.forRideLocked(session.accountId(), UUID.fromString((String) search.getFirst()[2]));
            for (Timed t : timed) {
                // Recheck: still online and fresh, still free, never proposed for this search.
                if (online(now).stream().noneMatch(c -> c.driver.equals(t.driver)) || hasActiveRide(t.driver) || proposedBefore(searchId, t.driver)) continue;
                Instant expires = earliest(earliest(now.plus(PROPOSAL), (Instant) search.getFirst()[1]), quote.expiresAt());
                jdbc.update("INSERT INTO ride_proposal (gate_hash,id,search_id,driver_account_id,pickup_seconds,state,sent_at,expires_at) VALUES (?,?,?,?,?,'sent',?,?)",
                        gate, UUID.randomUUID().toString(), searchId.toString(), t.driver, t.seconds, sql(now), sql(expires));
                return Boolean.TRUE;
            }
            return Boolean.FALSE;
        });
    }

    // ---- Driver proposals (A12-A13) ----

    /** A12: the driver's pending proposals with pickup and drop-off points and the price. */
    public List<ProposalView> proposals(char[] token, String client) {
        return unwrap(sessions.withAuthenticatedSession(token, client, session -> {
            Instant now = micros(session.serverTime());
            var rows = jdbc.query("SELECT p.id,p.pickup_seconds,p.expires_at,s.quote_id,s.client_account_id FROM ride_proposal p JOIN ride_search s "
                    + "ON s.gate_hash=p.gate_hash AND s.id=p.search_id WHERE p.gate_hash=? AND p.driver_account_id=? AND p.state='sent' AND p.expires_at>? "
                    + "AND s.state='searching' ORDER BY p.expires_at", (rs, n) -> new Object[] { rs.getString(1), rs.getInt(2),
                    instant(rs.getObject(3, LocalDateTime.class)), rs.getString(4), rs.getString(5) },
                    gate, session.accountId().toString(), sql(now));
            List<ProposalView> views = new ArrayList<>();
            for (Object[] r : rows) {
                var quote = quotes.forRideLocked(UUID.fromString((String) r[4]), UUID.fromString((String) r[3]));
                FareCalculation fare = fare(quote);
                views.add(new ProposalView(UUID.fromString((String) r[0]), quote.mode(), quote.passengerCount(), fare.priceXaf(),
                        fare.driverXaf(), (Integer) r[1], (Instant) r[2], quote.origin(), quote.destination()));
            }
            return new Out<>(List.copyOf(views), null);
        }));
    }

    /**
     * A13: before the proposal, search and quote deadlines, the driver accepts (the car's single active
     * reservation is created, waiting for payment for three minutes) or refuses (the search goes on).
     */
    public RideView respond(char[] token, String client, UUID proposalId, boolean accept) {
        if (proposalId == null) throw invalid();
        return unwrap(sessions.withAuthenticatedSession(token, client, session -> {
            Instant now = micros(session.serverTime());
            String actor = session.accountId().toString();
            expireHolds(now);
            var rows = jdbc.query("SELECT search_id,state,expires_at FROM ride_proposal WHERE gate_hash=? AND id=? AND driver_account_id=? FOR UPDATE",
                    (rs, n) -> new Object[] { rs.getString(1), rs.getString(2), instant(rs.getObject(3, LocalDateTime.class)) },
                    gate, proposalId.toString(), actor);
            if (rows.isEmpty()) return new Out<RideView>(null, RideException.Code.NOT_FOUND);
            String searchId = (String) rows.getFirst()[0];
            var search = jdbc.query("SELECT state,expires_at,quote_id,client_account_id,reservation_id FROM ride_search WHERE gate_hash=? AND id=? FOR UPDATE",
                    (rs, n) -> new Object[] { rs.getString(1), instant(rs.getObject(2, LocalDateTime.class)), rs.getString(3), rs.getString(4),
                            rs.getString(5) }, gate, searchId).getFirst();
            if ("accepted".equals(rows.getFirst()[1]) && search[4] != null) return new Out<>(ride((String) search[4], actor), null);
            if (!"sent".equals(rows.getFirst()[1]) || !now.isBefore((Instant) rows.getFirst()[2])) return new Out<RideView>(null, RideException.Code.PROPOSAL_EXPIRED);
            if (!accept) {
                jdbc.update("UPDATE ride_proposal SET state='refused',responded_at=? WHERE gate_hash=? AND id=?", sql(now), gate, proposalId.toString());
                return new Out<RideView>(null, null);
            }
            var quote = quotes.forRideLocked(UUID.fromString((String) search[3]), UUID.fromString((String) search[2]));
            if (!"searching".equals(search[0]) || !now.isBefore((Instant) search[1]) || !now.isBefore(quote.expiresAt())) {
                return new Out<RideView>(null, RideException.Code.PROPOSAL_EXPIRED);
            }
            jdbc.query("SELECT account_id FROM driver_presence WHERE gate_hash=? AND account_id=? FOR UPDATE", (rs, n) -> rs.getString(1), gate, actor);
            if (hasActiveRide(actor)) return new Out<RideView>(null, RideException.Code.CAR_BUSY);
            FareCalculation fare = fare(quote);
            if (fare.priceXaf() != quote.totalXaf()) return new Out<RideView>(null, RideException.Code.SERVICE_UNAVAILABLE);
            String id = UUID.randomUUID().toString(), code = String.format("%06d", random.nextInt(1_000_000));
            jdbc.update("INSERT INTO ride_reservation (gate_hash,id,search_id,client_account_id,driver_account_id,mode,seats,price_xaf,commission_xaf,driver_xaf,fare_version,state,active_marker,hold_expires_at,key_id,boarding_code_mac,boarding_code_cipher,created_at) "
                    + "VALUES (?,?,?,?,?,?,?,?,?,?,?,'awaiting_payment',1,?,?,?,?,?)", gate, id, searchId, search[3], actor, quote.mode(),
                    quote.passengerCount(), fare.priceXaf(), fare.commissionXaf(), fare.driverXaf(), fare.fareVersion(), sql(now.plus(PAYMENT_WINDOW)),
                    crypto.keyId(), codeMac(id, code), crypto.encrypt(code.getBytes(StandardCharsets.US_ASCII), codeAad(id, crypto.keyId())), sql(now));
            jdbc.update("UPDATE ride_proposal SET state='accepted',responded_at=? WHERE gate_hash=? AND id=?", sql(now), gate, proposalId.toString());
            jdbc.update("UPDATE ride_search SET state='matched',reservation_id=? WHERE gate_hash=? AND id=?", id, gate, searchId);
            return new Out<>(ride(id, actor), null);
        }));
    }

    // ---- The ride (A18, A21, A22) ----

    public RideView ride(char[] token, String client, UUID rideId) {
        if (rideId == null) throw invalid();
        return unwrap(sessions.withAuthenticatedSession(token, client, session -> {
            expireHolds(micros(session.serverTime()));
            String actor = session.accountId().toString();
            if (!party(rideId.toString(), actor)) return new Out<RideView>(null, RideException.Code.NOT_FOUND);
            return new Out<>(ride(rideId.toString(), actor), null);
        }));
    }

    /**
     * The account's ride still in progress, as client or as driver (the most recent one), or null: lets a phone that was
     * closed find its ride again.
     */
    public RideView activeRide(char[] token, String client) {
        return unwrap(sessions.withAuthenticatedSession(token, client, session -> {
            expireHolds(micros(session.serverTime()));
            String actor = session.accountId().toString();
            var ids = jdbc.queryForList("SELECT id FROM ride_reservation WHERE gate_hash=? AND (client_account_id=? OR driver_account_id=?) "
                    + "AND state IN ('awaiting_payment','confirmed','driver_arrived','boarded') ORDER BY created_at DESC LIMIT 1",
                    String.class, gate, actor, actor);
            return new Out<>(ids.isEmpty() ? null : ride(ids.getFirst(), actor), null);
        }));
    }

    /** The driver at the agreed point: arrive, board (client code), finish, or declare the client absent after five minutes. */
    public RideView driverAction(char[] token, String client, UUID rideId, String action, String code) {
        if (rideId == null || !Set.of("arrive", "board", "finish", "client_absent", "cancel").contains(action)) throw invalid();
        if ("board".equals(action) && (code == null || !code.matches("[0-9]{6}"))) throw invalid();
        return unwrap(sessions.withAuthenticatedSession(token, client, session -> {
            Instant now = micros(session.serverTime());
            String actor = session.accountId().toString();
            expireHolds(now);
            var rows = jdbc.query("SELECT state,arrived_at,boarding_code_mac,price_xaf FROM ride_reservation WHERE gate_hash=? AND id=? AND driver_account_id=? FOR UPDATE",
                    (rs, n) -> new Object[] { rs.getString(1), rs.getObject(2, LocalDateTime.class), rs.getString(3), rs.getLong(4) },
                    gate, rideId.toString(), actor);
            if (rows.isEmpty()) return new Out<RideView>(null, RideException.Code.NOT_FOUND);
            String state = (String) rows.getFirst()[0];
            String id = rideId.toString();
            switch (action) {
                case "arrive" -> {
                    if ("driver_arrived".equals(state)) break;
                    if (!"confirmed".equals(state)) return new Out<RideView>(null, RideException.Code.NOT_PAID);
                    jdbc.update("UPDATE ride_reservation SET state='driver_arrived',arrived_at=? WHERE gate_hash=? AND id=?", sql(now), gate, id);
                }
                case "board" -> {
                    if ("boarded".equals(state)) break;
                    if (!"driver_arrived".equals(state) && !"confirmed".equals(state)) return new Out<RideView>(null, RideException.Code.NOT_PAID);
                    if (!same((String) rows.getFirst()[2], codeMac(id, code))) return new Out<RideView>(null, RideException.Code.WRONG_CODE);
                    jdbc.update("UPDATE ride_reservation SET state='boarded',boarded_at=? WHERE gate_hash=? AND id=?", sql(now), gate, id);
                }
                case "finish" -> {
                    if ("finished".equals(state)) break;
                    if (!"boarded".equals(state)) return new Out<RideView>(null, RideException.Code.NOT_BOARDED);
                    jdbc.update("UPDATE ride_reservation SET state='finished',ended_at=?,active_marker=NULL WHERE gate_hash=? AND id=?", sql(now), gate, id);
                    // R14: the paid price goes to the driver and to WAKA as R03 settled it.
                    var split = jdbc.queryForList("SELECT driver_xaf,commission_xaf FROM ride_reservation WHERE gate_hash=? AND id=?", gate, id).getFirst();
                    com.waka.finance.Ledger.release(jdbc, gate, com.waka.finance.Ledger.hold("ride", id), now, com.waka.finance.Ledger.parts(
                            com.waka.finance.Ledger.earnings(actor), (Number) split.get("driver_xaf"), com.waka.finance.Ledger.REVENUE, (Number) split.get("commission_xaf")));
                    com.waka.drivers.SubscriptionService.startTrialIfFirst(jdbc, gate, actor, now, "ride_reservation", id);
                }
                case "client_absent" -> {
                    if ("client_absent".equals(state)) break;
                    LocalDateTime arrived = (LocalDateTime) rows.getFirst()[1];
                    if (!"driver_arrived".equals(state) || arrived == null) return new Out<RideView>(null, RideException.Code.NOT_ARRIVED);
                    if (now.isBefore(instant(arrived).plus(WAIT))) return new Out<RideView>(null, RideException.Code.TOO_EARLY);
                    // R08/R13: no automatic penalty; the client is refunded and a human review stays possible.
                    end(id, "client_absent", now, (Long) rows.getFirst()[3]);
                }
                default -> {
                    if ("cancelled_by_driver".equals(state)) break;
                    if (!Set.of("awaiting_payment", "confirmed", "driver_arrived").contains(state)) return new Out<RideView>(null, RideException.Code.ALREADY_BOARDED);
                    end(id, "cancelled_by_driver", now, "awaiting_payment".equals(state) ? null : (Long) rows.getFirst()[3]);
                }
            }
            return new Out<>(ride(id, actor), null);
        }));
    }

    /** A21: the client cancels before boarding; R08: full refund of what was paid, no penalty. */
    public RideView cancelByClient(char[] token, String client, UUID rideId) {
        if (rideId == null) throw invalid();
        return unwrap(sessions.withAuthenticatedSession(token, client, session -> {
            Instant now = micros(session.serverTime());
            String actor = session.accountId().toString();
            expireHolds(now);
            var rows = jdbc.query("SELECT state,price_xaf FROM ride_reservation WHERE gate_hash=? AND id=? AND client_account_id=? FOR UPDATE",
                    (rs, n) -> new Object[] { rs.getString(1), rs.getLong(2) }, gate, rideId.toString(), actor);
            if (rows.isEmpty()) return new Out<RideView>(null, RideException.Code.NOT_FOUND);
            String state = (String) rows.getFirst()[0];
            if (Set.of("awaiting_payment", "confirmed", "driver_arrived").contains(state)) {
                end(rideId.toString(), "cancelled_by_client", now, "awaiting_payment".equals(state) ? null : (Long) rows.getFirst()[1]);
            } else if (!state.startsWith("cancelled") && !"expired".equals(state)) {
                return new Out<RideView>(null, RideException.Code.ALREADY_BOARDED);
            }
            return new Out<>(ride(rideId.toString(), actor), null);
        }));
    }

    // ---- Helpers ----

    /** An unpaid reservation past its payment window frees the car (lazy, inside each transaction). */
    private void expireHolds(Instant now) {
        jdbc.update("UPDATE ride_reservation SET state='expired',active_marker=NULL,ended_at=? WHERE gate_hash=? AND state='awaiting_payment' AND hold_expires_at<=?",
                sql(now), gate, sql(now));
    }
    private void end(String id, String state, Instant now, Long refund) {
        jdbc.update("UPDATE ride_reservation SET state=?,ended_at=?,active_marker=NULL,refund_xaf=? WHERE gate_hash=? AND id=?",
                state, sql(now), refund, gate, id);
        if (refund != null) {
            // R08/R14: the refund lands in the client's WAKA credit at once.
            String payer = jdbc.queryForObject("SELECT client_account_id FROM ride_reservation WHERE gate_hash=? AND id=?", String.class, gate, id);
            com.waka.finance.Ledger.release(jdbc, gate, com.waka.finance.Ledger.hold("ride", id), now,
                    com.waka.finance.Ledger.parts(com.waka.finance.Ledger.account(payer), refund));
        }
    }
    private List<Candidate> online(Instant now) {
        return jdbc.query("SELECT account_id,key_id,position_cipher FROM driver_presence WHERE gate_hash=? AND online=1 AND position_at>=?",
                (rs, n) -> {
                    if (!crypto.keyId().equals(rs.getString(2))) return null;
                    String[] p = new String(crypto.decrypt(rs.getBytes(3), positionAad(rs.getString(1), rs.getString(2))), StandardCharsets.US_ASCII).split(",");
                    return new Candidate(rs.getString(1), new GeoPoint(new BigDecimal(p[0]), new BigDecimal(p[1])));
                }, gate, sql(now.minus(POSITION_FRESHNESS))).stream().filter(Objects::nonNull).toList();
    }
    private record Seen(GeoPoint position, Instant at) { }
    /** The driver's own fresh position (online, less than a minute old) with its server time, or null. */
    private Seen driverPosition(String driver) {
        Instant since = micros(clock.instant()).minus(POSITION_FRESHNESS);
        return jdbc.query("SELECT account_id,key_id,position_cipher,position_at FROM driver_presence WHERE gate_hash=? AND account_id=? AND online=1 AND position_at>=?",
                (rs, n) -> {
                    if (!crypto.keyId().equals(rs.getString(2))) return null;
                    String[] p = new String(crypto.decrypt(rs.getBytes(3), positionAad(rs.getString(1), rs.getString(2))), StandardCharsets.US_ASCII).split(",");
                    return new Seen(new GeoPoint(new BigDecimal(p[0]), new BigDecimal(p[1])), instant(rs.getObject(4, LocalDateTime.class)));
                }, gate, driver, sql(since)).stream().filter(Objects::nonNull).findFirst().orElse(null);
    }
    private boolean liveProposal(UUID searchId, Instant now) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM ride_proposal WHERE gate_hash=? AND search_id=? AND state='sent' AND expires_at>?",
                Integer.class, gate, searchId.toString(), sql(now));
        return n != null && n > 0;
    }
    private boolean proposedBefore(UUID searchId, String driver) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM ride_proposal WHERE gate_hash=? AND search_id=? AND driver_account_id=?",
                Integer.class, gate, searchId.toString(), driver);
        return n != null && n > 0;
    }
    private boolean hasActiveRide(String driver) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM ride_reservation WHERE gate_hash=? AND driver_account_id=? AND active_marker=1",
                Integer.class, gate, driver);
        return n != null && n > 0;
    }
    private boolean party(String rideId, String actor) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM ride_reservation WHERE gate_hash=? AND id=? AND (client_account_id=? OR driver_account_id=?)",
                Integer.class, gate, rideId, actor, actor);
        return n != null && n > 0;
    }
    private FareCalculation fare(PersistentQuoteService.QuoteForRide quote) {
        var schedule = fares.findByVersion(quote.fareVersion());
        BigDecimal metres = BigDecimal.valueOf(quote.distanceMetres());
        return "vip".equals(quote.mode()) ? engine.vip(schedule, metres, false, false) : engine.shared(schedule, metres, quote.passengerCount(), false, false);
    }
    private SearchView searchView(String id, String actor, Instant now, boolean replay) {
        var row = jdbc.query("SELECT state,expires_at,reservation_id FROM ride_search WHERE gate_hash=? AND id=?",
                (rs, n) -> new Object[] { rs.getString(1), instant(rs.getObject(2, LocalDateTime.class)), rs.getString(3) }, gate, id).getFirst();
        String state = (String) row[0];
        if ("searching".equals(state) && !now.isBefore((Instant) row[1])) state = "expired";
        return new SearchView(UUID.fromString(id), state, (Instant) row[1], "searching".equals(state) && liveProposal(UUID.fromString(id), now),
                row[2] == null ? null : ride((String) row[2], actor), replay);
    }
    /** Each party sees the price; only the client sees the boarding code; points come from the quote. */
    private RideView ride(String id, String viewer) {
        return jdbc.query("SELECT r.*,s.quote_id FROM ride_reservation r JOIN ride_search s ON s.gate_hash=r.gate_hash AND s.id=r.search_id WHERE r.gate_hash=? AND r.id=?", (rs, n) -> {
            String clientId = rs.getString("client_account_id");
            boolean isClient = viewer.equals(clientId);
            String state = rs.getString("state");
            String code = null;
            if (isClient && ACTIVE.contains(state)) {
                if (!crypto.keyId().equals(rs.getString("key_id"))) throw new RideException(RideException.Code.SERVICE_UNAVAILABLE);
                code = new String(crypto.decrypt(rs.getBytes("boarding_code_cipher"), codeAad(id, rs.getString("key_id"))), StandardCharsets.US_ASCII);
            }
            var quote = quotes.forRideLocked(UUID.fromString(clientId), UUID.fromString(rs.getString("quote_id")));
            LocalDateTime arrived = rs.getObject("arrived_at", LocalDateTime.class);
            Seen driver = isClient && COMING.contains(state) ? driverPosition(rs.getString("driver_account_id")) : null;
            return new RideView(UUID.fromString(id), rs.getString("mode"), rs.getInt("seats"), rs.getLong("price_xaf"), rs.getLong("driver_xaf"),
                    state, instant(rs.getObject("hold_expires_at", LocalDateTime.class)), arrived == null ? null : instant(arrived),
                    isClient ? "client" : "driver", code, (Long) rs.getObject("refund_xaf", Long.class), quote.origin(), quote.destination(),
                    driver == null ? null : driver.position(), driver == null ? null : driver.at());
        }, gate, id).getFirst();
    }
    private String codeMac(String rideId, String code) { return crypto.requestMac(rideId, "ride-boarding-code-v1", code); }
    private byte[] codeAad(String rideId, String keyId) { return ProfileCrypto.aad("waka-ride-boarding-code-v1", gate, rideId, keyId); }
    private byte[] positionAad(String driver, String keyId) { return ProfileCrypto.aad("waka-driver-position-v1", gate, driver, keyId); }
    private static boolean same(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.US_ASCII), b.getBytes(StandardCharsets.US_ASCII));
    }
    private static Instant earliest(Instant a, Instant b) { return a.isBefore(b) ? a : b; }
    private static Instant micros(Instant value) { return value.truncatedTo(ChronoUnit.MICROS); }
    private static LocalDateTime sql(Instant value) { return LocalDateTime.ofInstant(value, ZoneOffset.UTC); }
    private static Instant instant(LocalDateTime value) { return value.toInstant(ZoneOffset.UTC); }
    private static RideException invalid() { return new RideException(RideException.Code.INVALID_REQUEST); }
    private static <T> T unwrap(Out<T> out) {
        if (out == null) return null;
        if (out.refusal != null) throw new RideException(out.refusal);
        return out.value;
    }
    @Override public String toString() { return "PersistentRideService[REDACTED]"; }
}
