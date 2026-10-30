package com.waka.mobility.quote;

import com.waka.geo.GeoPoint;
import com.waka.geo.RouteDistanceProvider;
import com.waka.geo.ServiceArea;
import com.waka.identity.profile.ProfileCrypto;
import com.waka.identity.session.PersistentSessionService;
import com.waka.pricing.domain.FareCalculation;
import com.waka.pricing.domain.FareSchedule;
import com.waka.pricing.domain.PricingEngine;
import com.waka.pricing.domain.PricingException;
import com.waka.pricing.infrastructure.JdbcFareScheduleRepository;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * A08: a five-minute quote for the authenticated client. The distance comes only from the server's
 * route provider and the price only from the explicitly configured fare version; the client sends
 * neither. Nothing is reserved. Construct explicitly once the provider, the served territory and the
 * sale version are adopted; not assembled by default.
 */
public final class PersistentQuoteService {
    public static final Duration VALIDITY = Duration.ofMinutes(5);
    public enum Mode { SHARED, VIP }
    public record QuoteView(UUID quoteId, String mode, int passengerCount, int distanceMetres, String fareVersion,
            String bandCode, long placePriceXaf, long totalXaf, String currency, Instant createdAt, Instant expiresAt,
            boolean idempotentReplay) { }
    private record Lookup(QuoteView view, QuoteException.Code refusal) { }

    private final PersistentSessionService sessions;
    private final JdbcTemplate jdbc;
    private final ProfileCrypto crypto;
    private final JdbcFareScheduleRepository fares;
    private final String fareVersion;
    private final RouteDistanceProvider routes;
    private final ServiceArea area;
    private final Clock clock;
    private final PricingEngine engine = new PricingEngine();
    private final String gate;

    public PersistentQuoteService(PersistentSessionService sessions, JdbcTemplate jdbc, ProfileCrypto crypto,
            JdbcFareScheduleRepository fares, String fareVersion, RouteDistanceProvider routes, ServiceArea area, Clock clock) {
        this.sessions = Objects.requireNonNull(sessions);
        this.jdbc = Objects.requireNonNull(jdbc);
        this.crypto = Objects.requireNonNull(crypto);
        this.fares = Objects.requireNonNull(fares);
        this.routes = Objects.requireNonNull(routes);
        this.area = Objects.requireNonNull(area);
        this.clock = Objects.requireNonNull(clock);
        if (fareVersion == null || !fareVersion.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) {
            throw new IllegalArgumentException("QUOTE_FARE_VERSION_REQUIRED");
        }
        this.fareVersion = fareVersion;
        if (!sessions.usesDataSource(jdbc.getDataSource()) || !sessions.usesClock(clock)) {
            throw new IllegalArgumentException("QUOTE_DEPENDENCIES_MISMATCH");
        }
        if (crypto.reusesSessionKey(sessions)) throw new IllegalArgumentException("QUOTE_SESSION_KEY_REUSE");
        gate = sessions.identityGateHash();
    }

    /**
     * Shared: {@code passengerCount} paid seats (1 to 5). VIP: occupants (1 to 5) that never change the
     * price of 25/2 seats. The same key with the same request returns the same quote, even expired; with
     * another request it is refused. The provider is called outside the session transaction, after an
     * authenticated replay check, so a lost answer never costs a second provider call.
     */
    public QuoteView create(char[] token, String client, UUID key, Mode mode, int passengerCount,
            GeoPoint origin, GeoPoint destination) {
        if (key == null || mode == null || origin == null || destination == null || passengerCount < 1 || passengerCount > 5
                || origin.equals(destination)) {
            throw new QuoteException(QuoteException.Code.INVALID_REQUEST);
        }
        String payloadFields = mode.name() + "|" + passengerCount + "|" + origin.canonical() + "|" + destination.canonical();
        Lookup first = sessions.withAuthenticatedSession(token, client, session -> lookup(session.accountId(), key, payloadFields));
        if (first != null) return unwrap(first);
        if (!area.covers(origin) || !area.covers(destination)) throw new QuoteException(QuoteException.Code.OUTSIDE_SERVICE_AREA);
        BigDecimal distance;
        try { distance = routes.roadDistanceMetres(origin, destination); }
        catch (RouteDistanceProvider.RouteUnavailableException | RuntimeException hidden) {
            throw new QuoteException(QuoteException.Code.ROUTE_UNAVAILABLE);
        }
        if (distance == null) throw new QuoteException(QuoteException.Code.NO_ROUTE);
        FareCalculation calculation;
        try {
            FareSchedule schedule = fares.findByVersion(fareVersion);
            calculation = mode == Mode.VIP ? engine.vip(schedule, distance, false, false)
                    : engine.shared(schedule, distance, passengerCount, false, false);
        } catch (PricingException error) {
            throw new QuoteException(switch (error.code()) {
                case DISTANCE_OUT_OF_RANGE -> QuoteException.Code.DISTANCE_OUT_OF_RANGE;
                case INVALID_DISTANCE -> QuoteException.Code.ROUTE_UNAVAILABLE;
                default -> QuoteException.Code.SERVICE_UNAVAILABLE;
            });
        } catch (RuntimeException hidden) {
            throw new QuoteException(QuoteException.Code.SERVICE_UNAVAILABLE);
        }
        return unwrap(sessions.withAuthenticatedSession(token, client, session -> {
            // A concurrent identical command may have won meanwhile: it keeps the single effect.
            Lookup again = lookup(session.accountId(), key, payloadFields);
            if (again != null) return again;
            String actor = session.accountId().toString();
            UUID id = UUID.randomUUID();
            Instant now = session.serverTime().truncatedTo(ChronoUnit.MICROS), expires = now.plus(VALIDITY);
            byte[] points = (origin.canonical() + ";" + destination.canonical()).getBytes(StandardCharsets.US_ASCII);
            byte[] cipher = crypto.encrypt(points, pointsAad(actor, id, crypto.keyId()));
            jdbc.update("INSERT INTO quote (gate_hash,id,account_id,request_mac,payload_mac,mode,passenger_count,distance_metres,"
                    + "fare_version,band_code,place_price_xaf,total_xaf,key_id,points_cipher,created_at,expires_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    gate, id.toString(), actor, requestMac(actor, key), payloadMac(actor, payloadFields), mode.name().toLowerCase(),
                    passengerCount, calculation.distanceMetres(), calculation.fareVersion(), calculation.band().id(),
                    calculation.band().unitPriceXaf(), calculation.priceXaf(), crypto.keyId(), cipher, sql(now), sql(expires));
            return new Lookup(new QuoteView(id, mode.name().toLowerCase(), passengerCount, calculation.distanceMetres(),
                    calculation.fareVersion(), calculation.band().id(), calculation.band().unitPriceXaf(), calculation.priceXaf(),
                    calculation.currency(), now, expires, false), null);
        }));
    }

    /** {@code null} when the key is new for this account. */
    private Lookup lookup(UUID account, UUID key, String payloadFields) {
        String actor = account.toString();
        var rows = jdbc.query("SELECT * FROM quote WHERE gate_hash=? AND request_mac=? FOR UPDATE", (rs, n) -> new Object[] {
                rs.getString("payload_mac"), new QuoteView(UUID.fromString(rs.getString("id")), rs.getString("mode"),
                        rs.getInt("passenger_count"), rs.getInt("distance_metres"), rs.getString("fare_version"),
                        rs.getString("band_code"), rs.getLong("place_price_xaf"), rs.getLong("total_xaf"), "XAF",
                        instant(rs.getObject("created_at", LocalDateTime.class)),
                        instant(rs.getObject("expires_at", LocalDateTime.class)), true) },
                gate, requestMac(actor, key));
        if (rows.isEmpty()) return null;
        if (!MessageDigest.isEqual(((String) rows.getFirst()[0]).getBytes(StandardCharsets.US_ASCII),
                payloadMac(actor, payloadFields).getBytes(StandardCharsets.US_ASCII))) {
            return new Lookup(null, QuoteException.Code.IDEMPOTENCY_KEY_REUSED);
        }
        return new Lookup((QuoteView) rows.getFirst()[1], null);
    }

    private String requestMac(String actor, UUID key) { return crypto.requestMac(actor, "quote-create-v1", key.toString()); }
    // The payload mac reuses the profile primitive's domain-separated encoding with a quote-specific field.
    private String payloadMac(String actor, String fields) { return crypto.requestMac(actor, "quote-payload-v1", fields); }
    /** What a city ride needs from its quote, read inside the caller's transaction (R13). */
    public record QuoteForRide(UUID quoteId, String mode, int passengerCount, int distanceMetres, long totalXaf, String fareVersion,
            Instant expiresAt, GeoPoint origin, GeoPoint destination) {
        @Override public String toString() { return "QuoteForRide[REDACTED]"; }
    }

    /** The client's own quote with its decrypted points, or null when unknown or another account's. */
    public QuoteForRide forRideLocked(UUID client, UUID quoteId) {
        var rows = jdbc.query("SELECT * FROM quote WHERE gate_hash=? AND id=? AND account_id=? FOR UPDATE", (rs, n) -> {
            if (!crypto.keyId().equals(rs.getString("key_id"))) throw new QuoteException(QuoteException.Code.SERVICE_UNAVAILABLE);
            String[] points = new String(crypto.decrypt(rs.getBytes("points_cipher"),
                    pointsAad(client.toString(), quoteId, rs.getString("key_id"))), StandardCharsets.US_ASCII).split(";");
            return new QuoteForRide(quoteId, rs.getString("mode"), rs.getInt("passenger_count"), rs.getInt("distance_metres"),
                    rs.getLong("total_xaf"), rs.getString("fare_version"), instant(rs.getObject("expires_at", LocalDateTime.class)),
                    point(points[0]), point(points[1]));
        }, gate, quoteId.toString(), client.toString());
        return rows.isEmpty() ? null : rows.getFirst();
    }
    private static GeoPoint point(String canonical) {
        String[] parts = canonical.split(",");
        return new GeoPoint(new BigDecimal(parts[0]), new BigDecimal(parts[1]));
    }

    private byte[] pointsAad(String actor, UUID id, String keyId) {
        return ProfileCrypto.aad("waka-quote-points-v1", gate, actor, id.toString(), keyId);
    }
    private static QuoteView unwrap(Lookup lookup) {
        if (lookup.refusal != null) throw new QuoteException(lookup.refusal);
        return lookup.view;
    }
    private static LocalDateTime sql(Instant value) { return LocalDateTime.ofInstant(value, ZoneOffset.UTC); }
    private static Instant instant(LocalDateTime value) { return value.toInstant(ZoneOffset.UTC); }
    @Override public String toString() { return "PersistentQuoteService[REDACTED]"; }
}
