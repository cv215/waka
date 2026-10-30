package com.waka.geo;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Road distance (R04) and road time to the pickup (R13) from the Google Routes API, {@code POST
 * /directions/v2:computeRoutes}, driving, without live traffic so the call stays in the Essentials tier.
 * Only {@code routes.distanceMeters} and {@code routes.duration} are asked (field mask). An answer without
 * any route means no road exists; anything else unreadable is "unavailable", never a guess from the points.
 * The key comes from the environment, never from Git; points are never logged.
 */
public final class GoogleRoutesProvider implements RouteDistanceProvider, PickupTimeProvider {
    public static final URI GOOGLE = URI.create("https://routes.googleapis.com");
    private static final int MAX_RESPONSE = 65536;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** Calls allowed per UTC day; beyond it the provider answers "unavailable" (Google's free tier is 10 000 a month). */
    public static final int DAILY_LIMIT = 300;

    private final HttpClient http;
    private final URI base;
    private final String key;
    private final Clock clock;
    private final int dailyLimit;
    private LocalDate day;
    private int used;

    public GoogleRoutesProvider(String key) { this(GOOGLE, key, Clock.systemUTC(), DAILY_LIMIT); }

    // Bounded test seam: only Google or a local http server on 127.0.0.1.
    GoogleRoutesProvider(URI base, String key) { this(base, key, Clock.systemUTC(), DAILY_LIMIT); }

    GoogleRoutesProvider(URI base, String key, Clock clock, int dailyLimit) {
        if (clock == null || dailyLimit < 1) throw new IllegalArgumentException("ROUTES_CONFIGURATION");
        this.clock = clock;
        this.dailyLimit = dailyLimit;
        boolean local = base != null && "http".equals(base.getScheme()) && "127.0.0.1".equals(base.getHost()) && base.getPort() > 0;
        if (base == null || !(GOOGLE.equals(base) || local) || key == null || !key.matches("[A-Za-z0-9_-]{20,100}")) {
            throw new IllegalArgumentException("ROUTES_CONFIGURATION");
        }
        this.base = base;
        this.key = key;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    private record Route(long metres, long seconds) {}

    @Override
    public BigDecimal roadDistanceMetres(GeoPoint origin, GeoPoint destination) throws RouteUnavailableException {
        Route route = route(origin, destination);
        return route == null ? null : BigDecimal.valueOf(route.metres());
    }

    @Override
    public Integer pickupSeconds(GeoPoint driver, GeoPoint pickup) throws RouteUnavailableException {
        Route route = route(driver, pickup);
        return route == null ? null : Math.toIntExact(route.seconds());
    }

    /** Counts the call against today's allowance; no call leaves once it is spent. */
    private synchronized void spend() throws RouteUnavailableException {
        LocalDate today = LocalDate.now(clock);
        if (!today.equals(day)) {
            day = today;
            used = 0;
        }
        if (used >= dailyLimit) throw new RouteUnavailableException();
        used++;
    }

    private Route route(GeoPoint from, GeoPoint to) throws RouteUnavailableException {
        if (from == null || to == null) throw new IllegalArgumentException("INVALID_POINT");
        spend();
        Map<String, Object> body = Map.of(
                "origin", waypoint(from),
                "destination", waypoint(to),
                "travelMode", "DRIVE",
                "routingPreference", "TRAFFIC_UNAWARE",
                "units", "METRIC");
        JsonNode json;
        try {
            HttpRequest request = HttpRequest.newBuilder(base.resolve("/directions/v2:computeRoutes")).timeout(Duration.ofSeconds(10))
                    .header("Accept", "application/json").header("Content-Type", "application/json")
                    .header("X-Goog-Api-Key", key).header("X-Goog-FieldMask", "routes.distanceMeters,routes.duration")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(body))).build();
            HttpResponse<java.io.InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            byte[] raw;
            try (var in = response.body()) { raw = in.readNBytes(MAX_RESPONSE + 1); }
            if (response.statusCode() != 200 || raw.length > MAX_RESPONSE) throw new RouteUnavailableException();
            json = JSON.readTree(raw);
        } catch (IOException | JacksonException error) {
            throw new RouteUnavailableException();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new RouteUnavailableException();
        }
        if (json == null || !json.isObject()) throw new RouteUnavailableException();
        JsonNode routes = json.get("routes");
        if (routes == null || (routes.isArray() && routes.isEmpty())) return null; // Google answers {} when no road joins the points
        if (!routes.isArray()) throw new RouteUnavailableException();
        JsonNode first = routes.get(0);
        JsonNode metres = first.get("distanceMeters"), duration = first.get("duration");
        // Google leaves out a zero distance (same point); the duration is always present.
        if ((metres != null && (!metres.isIntegralNumber() || metres.asLong() < 0 || metres.asLong() > 5_000_000))
                || duration == null || !duration.isString() || !duration.asString().matches("[0-9]{1,7}(\\.[0-9]{1,9})?s")) {
            throw new RouteUnavailableException();
        }
        String text = duration.asString();
        long seconds = new BigDecimal(text.substring(0, text.length() - 1)).setScale(0, java.math.RoundingMode.CEILING).longValueExact();
        return new Route(metres == null ? 0 : metres.asLong(), seconds);
    }

    private static Map<String, Object> waypoint(GeoPoint point) {
        return Map.of("location", Map.of("latLng", Map.of("latitude", point.latitude(), "longitude", point.longitude())));
    }

    @Override public String toString() { return "GoogleRoutesProvider[REDACTED]"; }
}
