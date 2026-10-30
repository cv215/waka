package com.waka.geo;

import java.io.IOException;
import java.math.RoundingMode;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Address search for the pickup and drop-off (Google Places API, New): {@code POST /v1/places:autocomplete} limited to
 * Cameroon and biased towards the launch city, then {@code GET /v1/places/{id}} asking only {@code location}
 * (Essentials tier). The phone's session token groups the typing and the final choice into one billed session.
 * The key stays on the server; queries and points are never logged; at most {@link #DAILY_LIMIT} calls a day.
 */
public final class GooglePlacesProvider {
    public static final URI GOOGLE = URI.create("https://places.googleapis.com");
    public static final int DAILY_LIMIT = 300;
    private static final int MAX_RESPONSE = 65536;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    public record Suggestion(String placeId, String mainText, String secondaryText) {
        @Override public String toString() { return "Suggestion[REDACTED]"; }
    }

    /** Google could not answer reliably, or today's allowance is spent. */
    public static final class PlacesUnavailableException extends Exception {
        public PlacesUnavailableException() { super("PLACES_UNAVAILABLE", null, false, false); }
    }

    private final HttpClient http;
    private final URI base;
    private final String key;
    private final GeoPoint centre;
    private final int radiusMetres;
    private final Clock clock;
    private final int dailyLimit;
    private LocalDate day;
    private int used;

    public GooglePlacesProvider(String key, GeoPoint centre, int radiusMetres) {
        this(GOOGLE, key, centre, radiusMetres, Clock.systemUTC(), DAILY_LIMIT);
    }

    // Bounded test seam: only Google or a local http server on 127.0.0.1.
    GooglePlacesProvider(URI base, String key, GeoPoint centre, int radiusMetres, Clock clock, int dailyLimit) {
        boolean local = base != null && "http".equals(base.getScheme()) && "127.0.0.1".equals(base.getHost()) && base.getPort() > 0;
        if (base == null || !(GOOGLE.equals(base) || local) || key == null || !key.matches("[A-Za-z0-9_-]{20,100}")
                || centre == null || radiusMetres < 1000 || radiusMetres > 50_000 || clock == null || dailyLimit < 1) {
            throw new IllegalArgumentException("PLACES_CONFIGURATION");
        }
        this.base = base;
        this.key = key;
        this.centre = centre;
        this.radiusMetres = radiusMetres;
        this.clock = clock;
        this.dailyLimit = dailyLimit;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    /** At most five places for what was typed (2 to 100 characters). */
    public List<Suggestion> suggestions(String query, String sessionToken) throws PlacesUnavailableException {
        if (query == null || query.strip().length() < 2 || query.length() > 100 || !token(sessionToken)) {
            throw new IllegalArgumentException("INVALID_QUERY");
        }
        Map<String, Object> body = Map.of(
                "input", query.strip(),
                "languageCode", "fr",
                "includedRegionCodes", List.of("cm"),
                "sessionToken", sessionToken,
                "locationBias", Map.of("circle", Map.of(
                        "center", Map.of("latitude", centre.latitude(), "longitude", centre.longitude()),
                        "radius", radiusMetres)));
        JsonNode json = send(HttpRequest.newBuilder(base.resolve("/v1/places:autocomplete"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(body))));
        JsonNode list = json.get("suggestions");
        List<Suggestion> out = new ArrayList<>();
        if (list == null) return out; // nothing matches
        if (!list.isArray()) throw new PlacesUnavailableException();
        for (JsonNode item : list) {
            JsonNode place = item.get("placePrediction");
            if (place == null) continue; // query predictions are not places
            String id = text(place.get("placeId"));
            JsonNode format = place.get("structuredFormat");
            String main = format == null ? null : text(format.path("mainText").get("text"));
            String secondary = format == null ? null : text(format.path("secondaryText").get("text"));
            if (main == null) main = text(place.path("text").get("text"));
            if (id == null || !id.matches("[A-Za-z0-9_-]{10,300}") || main == null || main.isBlank()) throw new PlacesUnavailableException();
            out.add(new Suggestion(id, cut(main), secondary == null ? "" : cut(secondary)));
            if (out.size() == 5) break;
        }
        return out;
    }

    /** The chosen place's point, six decimals; null when Google has no position for it. */
    public GeoPoint location(String placeId, String sessionToken) throws PlacesUnavailableException {
        if (placeId == null || !placeId.matches("[A-Za-z0-9_-]{10,300}") || !token(sessionToken)) {
            throw new IllegalArgumentException("INVALID_PLACE");
        }
        String query = "?languageCode=fr&sessionToken=" + URLEncoder.encode(sessionToken, StandardCharsets.UTF_8);
        JsonNode json = send(HttpRequest.newBuilder(base.resolve("/v1/places/" + placeId + query))
                .header("X-Goog-FieldMask", "location").GET());
        JsonNode location = json.get("location");
        if (location == null) return null;
        JsonNode lat = location.get("latitude"), lon = location.get("longitude");
        if (lat == null || lon == null || !lat.isNumber() || !lon.isNumber()) throw new PlacesUnavailableException();
        try {
            return new GeoPoint(lat.decimalValue().setScale(6, RoundingMode.HALF_UP), lon.decimalValue().setScale(6, RoundingMode.HALF_UP));
        } catch (IllegalArgumentException outOfRange) {
            throw new PlacesUnavailableException();
        }
    }

    private synchronized void spend() throws PlacesUnavailableException {
        LocalDate today = LocalDate.now(clock);
        if (!today.equals(day)) {
            day = today;
            used = 0;
        }
        if (used >= dailyLimit) throw new PlacesUnavailableException();
        used++;
    }

    private JsonNode send(HttpRequest.Builder request) throws PlacesUnavailableException {
        spend();
        try {
            HttpResponse<java.io.InputStream> response = http.send(request.timeout(Duration.ofSeconds(10))
                    .header("Accept", "application/json").header("X-Goog-Api-Key", key).build(), HttpResponse.BodyHandlers.ofInputStream());
            byte[] raw;
            try (var in = response.body()) { raw = in.readNBytes(MAX_RESPONSE + 1); }
            if (response.statusCode() != 200 || raw.length > MAX_RESPONSE) throw new PlacesUnavailableException();
            JsonNode json = JSON.readTree(raw);
            if (json == null || !json.isObject()) throw new PlacesUnavailableException();
            return json;
        } catch (IOException | JacksonException error) {
            throw new PlacesUnavailableException();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new PlacesUnavailableException();
        }
    }

    private static boolean token(String value) { return value != null && value.matches("[A-Za-z0-9_-]{16,64}"); }
    private static String text(JsonNode node) { return node != null && node.isString() ? node.asString() : null; }
    private static String cut(String value) { return value.length() > 120 ? value.substring(0, 120) : value; }

    @Override public String toString() { return "GooglePlacesProvider[REDACTED]"; }
}
