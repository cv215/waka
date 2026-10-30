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
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Real local HTTP against a stand-in of the Google Places API (New); no call leaves the machine. */
class GooglePlacesProviderTest {
    private static final String KEY = "AIzaTestKey0123456789abcdefghijklmnopq";
    private static final GeoPoint YAOUNDE = new GeoPoint(new BigDecimal("3.848"), new BigDecimal("11.502"));
    private final String session = UUID.randomUUID().toString();
    private HttpServer server;
    private GooglePlacesProvider provider;
    private final List<String> seen = new CopyOnWriteArrayList<>();
    private volatile String answer = "{}";
    private volatile int code = 200;

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            seen.add(exchange.getRequestMethod() + " " + exchange.getRequestURI() + " key="
                    + exchange.getRequestHeaders().getFirst("X-Goog-Api-Key") + " mask="
                    + exchange.getRequestHeaders().getFirst("X-Goog-FieldMask") + " " + body);
            byte[] out = answer.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(code, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
        provider = new GooglePlacesProvider(URI.create("http://127.0.0.1:" + server.getAddress().getPort()), KEY, YAOUNDE, 20_000,
                Clock.systemUTC(), 300);
    }
    @AfterEach void stop() { server.stop(0); }

    @Test void suggestionsAreLimitedToCameroonBiasedToTheCityAndAtMostFive() throws Exception {
        StringBuilder many = new StringBuilder("{\"suggestions\":[{\"queryPrediction\":{\"text\":{\"text\":\"mokolo\"}}}");
        for (int i = 0; i < 7; i++) {
            many.append(",{\"placePrediction\":{\"placeId\":\"ChIJplace000").append(i).append("\",\"text\":{\"text\":\"Marche Mokolo, Yaounde\"},")
                    .append("\"structuredFormat\":{\"mainText\":{\"text\":\"Marche Mokolo\"},\"secondaryText\":{\"text\":\"Yaounde, Cameroun\"}}}}");
        }
        answer = many.append("]}").toString();
        var list = provider.suggestions("  Mokolo ", session);
        assertEquals(5, list.size());
        assertEquals("ChIJplace0000", list.getFirst().placeId());
        assertEquals("Marche Mokolo", list.getFirst().mainText());
        assertEquals("Yaounde, Cameroun", list.getFirst().secondaryText());
        String call = seen.getFirst();
        assertTrue(call.startsWith("POST /v1/places:autocomplete key=" + KEY + " "));
        assertTrue(call.contains("\"input\":\"Mokolo\"") && call.contains("\"includedRegionCodes\":[\"cm\"]")
                && call.contains("\"languageCode\":\"fr\"") && call.contains("\"sessionToken\":\"" + session + "\"")
                && call.contains("\"radius\":20000") && call.contains("\"latitude\":3.848"), call);
        answer = "{}";
        assertTrue(provider.suggestions("zzzz", session).isEmpty(), "nothing matches");
    }

    @Test void locationAsksOnlyThePointAndKeepsSixDecimals() throws Exception {
        answer = "{\"location\":{\"latitude\":3.87654321,\"longitude\":11.51234567}}";
        GeoPoint point = provider.location("ChIJplace0000", session);
        assertEquals(new BigDecimal("3.876543"), point.latitude());
        assertEquals(new BigDecimal("11.512346"), point.longitude());
        String call = seen.getFirst();
        assertTrue(call.startsWith("GET /v1/places/ChIJplace0000?languageCode=fr&sessionToken=" + session + " key=" + KEY + " mask=location "), call);
        answer = "{}";
        assertNull(provider.location("ChIJplace0000", session));
    }

    @Test void badInputNeverReachesGoogleAndBadAnswersAreUnavailable() {
        assertThrows(IllegalArgumentException.class, () -> provider.suggestions("a", session));
        assertThrows(IllegalArgumentException.class, () -> provider.suggestions("x".repeat(101), session));
        assertThrows(IllegalArgumentException.class, () -> provider.suggestions("Mokolo", "short"));
        assertThrows(IllegalArgumentException.class, () -> provider.location("../../evil?x=1", session));
        assertTrue(seen.isEmpty());
        for (String bad : List.of("{\"suggestions\":{}}", "{\"suggestions\":[{\"placePrediction\":{\"placeId\":\"x\"}}]}", "[]", "nope")) {
            answer = bad;
            assertThrows(GooglePlacesProvider.PlacesUnavailableException.class, () -> provider.suggestions("Mokolo", session), bad);
        }
        answer = "{\"location\":{\"latitude\":\"3.8\",\"longitude\":11.5}}";
        assertThrows(GooglePlacesProvider.PlacesUnavailableException.class, () -> provider.location("ChIJplace0000", session));
        code = 403;
        answer = "{}";
        assertThrows(GooglePlacesProvider.PlacesUnavailableException.class, () -> provider.suggestions("Mokolo", session));
    }

    @Test void dailyAllowanceStopsCallsAndTheKeyIsNeverPrinted() throws Exception {
        var limited = new GooglePlacesProvider(URI.create("http://127.0.0.1:" + server.getAddress().getPort()), KEY, YAOUNDE, 20_000,
                Clock.systemUTC(), 1);
        limited.suggestions("Mokolo", session);
        assertThrows(GooglePlacesProvider.PlacesUnavailableException.class, () -> limited.suggestions("Mokolo", session));
        assertEquals(1, seen.size());
        assertFalse(limited.toString().contains(KEY));
        assertThrows(IllegalArgumentException.class, () -> new GooglePlacesProvider(URI.create("https://evil.example"), KEY, YAOUNDE, 20_000,
                Clock.systemUTC(), 300));
    }
}
