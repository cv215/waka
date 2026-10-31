package com.waka.geo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Real local HTTP against a stand-in of the Google Routes API (no call leaves the machine). */
class GoogleRoutesProviderTest {
    private static final String KEY = "AIzaTestKey0123456789abcdefghijklmnopq";
    private static final GeoPoint AKWA = new GeoPoint(new BigDecimal("4.0511"), new BigDecimal("9.7085"));
    private static final GeoPoint BONAPRISO = new GeoPoint(new BigDecimal("4.0350"), new BigDecimal("9.6930"));
    private HttpServer server;
    private GoogleRoutesProvider provider;
    private final List<String> seen = new CopyOnWriteArrayList<>();
    private volatile String answer = "{}";
    private volatile int code = 200;

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            seen.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath() + " key="
                    + exchange.getRequestHeaders().getFirst("X-Goog-Api-Key") + " mask="
                    + exchange.getRequestHeaders().getFirst("X-Goog-FieldMask") + " " + body);
            byte[] out = answer.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(code, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
        provider = new GoogleRoutesProvider(URI.create("http://127.0.0.1:" + server.getAddress().getPort()), KEY);
    }
    @AfterEach void stop() { server.stop(0); }

    @Test void asksOnlyDistanceAndDurationByCarWithoutLiveTraffic() throws Exception {
        answer = "{\"routes\":[{\"distanceMeters\":3412,\"duration\":\"611s\"}]}";
        assertEquals(new BigDecimal(3412), provider.roadDistanceMetres(AKWA, BONAPRISO));
        assertEquals(611, provider.pickupSeconds(AKWA, BONAPRISO));
        String call = seen.getFirst();
        assertTrue(call.startsWith("POST /directions/v2:computeRoutes key=" + KEY + " mask=routes.distanceMeters,routes.duration "));
        assertTrue(call.contains("\"travelMode\":\"DRIVE\"") && call.contains("\"routingPreference\":\"TRAFFIC_UNAWARE\"")
                && call.contains("\"latitude\":4.0511") && call.contains("\"longitude\":9.693"));
    }

    @Test void aQuoteGetsTheShapeInTheSameCallAndABadShapeNeverBlocksThePrice() throws Exception {
        answer = "{\"routes\":[{\"distanceMeters\":3412,\"duration\":\"611s\",\"polyline\":{\"encodedPolyline\":\"_p~iF~ps|U_ulLnnqC\"}}]}";
        var road = provider.roadRoute(AKWA, BONAPRISO);
        assertEquals(new BigDecimal(3412), road.metres());
        assertEquals("_p~iF~ps|U_ulLnnqC", road.path());
        assertEquals(1, seen.size(), "one call for both");
        assertTrue(seen.getFirst().contains("mask=routes.distanceMeters,routes.duration,routes.polyline.encodedPolyline ")
                && seen.getFirst().contains("\"polylineQuality\":\"OVERVIEW\""));
        answer = "{\"routes\":[{\"distanceMeters\":3412,\"duration\":\"611s\",\"polyline\":{\"encodedPolyline\":\"bad shape\"}}]}";
        road = provider.roadRoute(AKWA, BONAPRISO);
        assertEquals(new BigDecimal(3412), road.metres());
        assertNull(road.path());
        answer = "{}";
        assertNull(provider.roadRoute(AKWA, BONAPRISO));
    }

    @Test void noRoadIsNullAndSamePointIsZero() throws Exception {
        answer = "{}";
        assertNull(provider.roadDistanceMetres(AKWA, BONAPRISO));
        answer = "{\"routes\":[]}";
        assertNull(provider.pickupSeconds(AKWA, BONAPRISO));
        answer = "{\"routes\":[{\"duration\":\"0s\"}]}";
        assertEquals(BigDecimal.ZERO, provider.roadDistanceMetres(AKWA, AKWA));
        answer = "{\"routes\":[{\"distanceMeters\":10,\"duration\":\"4.2s\"}]}";
        assertEquals(5, provider.pickupSeconds(AKWA, BONAPRISO), "fractions of a second are rounded up");
    }

    @Test void anythingElseIsUnavailableNeverAGuess() {
        for (String bad : List.of("{\"routes\":[{\"distanceMeters\":\"12\",\"duration\":\"5s\"}]}",
                "{\"routes\":[{\"distanceMeters\":-1,\"duration\":\"5s\"}]}", "{\"routes\":[{\"distanceMeters\":12}]}",
                "{\"routes\":[{\"distanceMeters\":12,\"duration\":\"5 minutes\"}]}", "{\"routes\":{}}", "[]", "not json")) {
            answer = bad;
            assertThrows(RouteDistanceProvider.RouteUnavailableException.class, () -> provider.roadDistanceMetres(AKWA, BONAPRISO), bad);
        }
        answer = "{\"routes\":[{\"distanceMeters\":12,\"duration\":\"5s\"}]}";
        code = 403;
        assertThrows(RouteDistanceProvider.RouteUnavailableException.class, () -> provider.roadDistanceMetres(AKWA, BONAPRISO));
        code = 429;
        assertThrows(RouteDistanceProvider.RouteUnavailableException.class, () -> provider.pickupSeconds(AKWA, BONAPRISO));
    }

    @Test void noCallLeavesOnceTheDailyAllowanceIsSpentAndItComesBackTheNextDay() throws Exception {
        answer = "{\"routes\":[{\"distanceMeters\":12,\"duration\":\"5s\"}]}";
        var now = new java.util.concurrent.atomic.AtomicReference<>(java.time.Instant.parse("2026-10-30T10:00:00Z"));
        java.time.Clock clock = new java.time.Clock() {
            @Override public java.time.ZoneId getZone() { return java.time.ZoneOffset.UTC; }
            @Override public java.time.Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public java.time.Instant instant() { return now.get(); }
        };
        var limited = new GoogleRoutesProvider(URI.create("http://127.0.0.1:" + server.getAddress().getPort()), KEY, clock, 2);
        limited.roadDistanceMetres(AKWA, BONAPRISO);
        limited.pickupSeconds(AKWA, BONAPRISO);
        assertThrows(RouteDistanceProvider.RouteUnavailableException.class, () -> limited.roadDistanceMetres(AKWA, BONAPRISO));
        assertEquals(2, seen.size(), "the third call never left");
        now.set(java.time.Instant.parse("2026-10-31T00:00:01Z"));
        assertEquals(new BigDecimal(12), limited.roadDistanceMetres(AKWA, BONAPRISO));
    }

    @Test void routeShapeAsksOnlyTheOverviewPolylineThroughTheStopsInOrder() throws Exception {
        var mvan = new GeoPoint(new BigDecimal("3.827"), new BigDecimal("11.515"));
        answer = "{\"routes\":[{\"polyline\":{\"encodedPolyline\":\"_p~iF~ps|U_ulLnnqC\"}}]}";
        assertEquals("_p~iF~ps|U_ulLnnqC", provider.encodedPath(List.of(AKWA, BONAPRISO, mvan)));
        String call = seen.getFirst();
        assertTrue(call.contains("mask=routes.polyline.encodedPolyline ") && call.contains("\"polylineQuality\":\"OVERVIEW\"")
                && call.indexOf("\"intermediates\"") < call.indexOf("\"latitude\":4.035") && call.contains("\"longitude\":9.693")
                && call.indexOf("\"latitude\":4.0511") < call.indexOf("\"latitude\":3.827"), call);
        answer = "{}";
        assertNull(provider.encodedPath(List.of(AKWA, BONAPRISO)), "no road");
        answer = "{\"routes\":[{\"polyline\":{\"encodedPolyline\":\"<script>\"}}]}";
        assertThrows(RouteDistanceProvider.RouteUnavailableException.class, () -> provider.encodedPath(List.of(AKWA, BONAPRISO)));
        assertThrows(IllegalArgumentException.class, () -> provider.encodedPath(List.of(AKWA)));
    }

    @Test void straightPathsAreEncodedLikeGoogle() {
        // Google's documented example: (38.5,-120.2), (40.7,-120.95), (43.252,-126.453).
        assertEquals("_p~iF~ps|U_ulLnnqC_mqNvxq`@", Polylines.encode(List.of(
                new GeoPoint(new BigDecimal("38.5"), new BigDecimal("-120.2")),
                new GeoPoint(new BigDecimal("40.7"), new BigDecimal("-120.95")),
                new GeoPoint(new BigDecimal("43.252"), new BigDecimal("-126.453")))));
        assertTrue(Polylines.valid("_p~iF~ps|U"));
        assertFalse(Polylines.valid("a b"));
    }

    @Test void onlyGoogleOrALocalStandInAndAKeyThatIsNeverPrinted() {
        assertThrows(IllegalArgumentException.class, () -> new GoogleRoutesProvider(URI.create("https://evil.example"), KEY));
        assertThrows(IllegalArgumentException.class, () -> new GoogleRoutesProvider("short"));
        assertThrows(IllegalArgumentException.class, () -> new GoogleRoutesProvider("Windows PowerShell Copyright (C) Microsoft"));
        assertFalse(new GoogleRoutesProvider(KEY).toString().contains(KEY));
    }
}
