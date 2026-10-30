package com.waka.identity.api;

import com.waka.geo.GooglePlacesProvider;
import com.waka.identity.session.PersistentSessionService;
import com.waka.identity.session.SessionException;
import jakarta.servlet.http.HttpServletRequest;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.json.JsonFactory;

/**
 * Address search for a city ride (K35): suggestions while typing, then the chosen place's point. Signed-in accounts
 * only; the maps key never leaves the server. POST bodies keep what was typed out of URLs and logs.
 */
@RestController
public final class PlaceController {
    private static final JsonFactory JSON = new JsonFactory();
    private final ObjectProvider<PersistentSessionService> sessions;
    private final ObjectProvider<GooglePlacesProvider> places;

    public PlaceController(ObjectProvider<PersistentSessionService> sessions, ObjectProvider<GooglePlacesProvider> places) {
        this.sessions = sessions;
        this.places = places;
    }

    public record SuggestionView(String placeId, String mainText, String secondaryText) { }
    public record LocationView(String placeId, java.math.BigDecimal latitude, java.math.BigDecimal longitude) { }

    /** Body {query, sessionToken}: at most five places in Cameroon, closest to the launch city first. */
    @PostMapping(path = "/api/v1/places/suggestions", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<SuggestionView>> suggestions(HttpServletRequest request) throws IOException {
        Map<String, String> body = read(request, Set.of("query", "sessionToken"));
        GooglePlacesProvider provider = signedIn(request);
        try {
            var list = provider.suggestions(body.get("query"), body.get("sessionToken"));
            return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                    .body(list.stream().map(s -> new SuggestionView(s.placeId(), s.mainText(), s.secondaryText())).toList());
        } catch (IllegalArgumentException invalid) {
            throw LoginRequestReader.invalid();
        } catch (GooglePlacesProvider.PlacesUnavailableException unavailable) {
            throw new ProfileHttpException(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE");
        }
    }

    /** Body {placeId, sessionToken}: the place's point (six decimals). 404 when Google has no position for it. */
    @PostMapping(path = "/api/v1/places/locations", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<LocationView> location(HttpServletRequest request) throws IOException {
        Map<String, String> body = read(request, Set.of("placeId", "sessionToken"));
        GooglePlacesProvider provider = signedIn(request);
        try {
            var point = provider.location(body.get("placeId"), body.get("sessionToken"));
            if (point == null) throw new ProfileHttpException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
            return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                    .body(new LocationView(body.get("placeId"), point.latitude(), point.longitude()));
        } catch (IllegalArgumentException invalid) {
            throw LoginRequestReader.invalid();
        } catch (GooglePlacesProvider.PlacesUnavailableException unavailable) {
            throw new ProfileHttpException(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE");
        }
    }

    /** The session is checked before anything is sent to Google. */
    private GooglePlacesProvider signedIn(HttpServletRequest request) {
        var service = sessions.getIfAvailable();
        var provider = places.getIfAvailable();
        if (service == null || provider == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        char[] token = MeRequestReader.bearer(request);
        try {
            String client = MeRequestReader.client(request);
            service.withAuthenticatedSession(token, client, session -> Boolean.TRUE);
            return provider;
        } catch (SessionException error) {
            throw new ResponseStatusException(error.code() == SessionException.Code.SERVICE_UNAVAILABLE
                    ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.UNAUTHORIZED);
        } finally { Arrays.fill(token, '\0'); }
    }

    /** Exactly the given string fields. */
    private static Map<String, String> read(HttpServletRequest request, Set<String> fields) throws IOException {
        if (request.getQueryString() != null) throw LoginRequestReader.invalid();
        byte[] bytes = request.getInputStream().readNBytes(1025);
        try {
            if (bytes.length > 1024) throw new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE);
            try (JsonParser parser = JSON.createParser(new InputStreamReader(new ByteArrayInputStream(bytes),
                    StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)))) {
                if (parser.nextToken() != JsonToken.START_OBJECT) throw LoginRequestReader.invalid();
                Map<String, String> out = new HashMap<>();
                while (parser.nextToken() != JsonToken.END_OBJECT) {
                    String name = parser.getString();
                    if (!fields.contains(name) || out.containsKey(name) || parser.nextToken() != JsonToken.VALUE_STRING) {
                        throw LoginRequestReader.invalid();
                    }
                    out.put(name, parser.getString());
                }
                if (!out.keySet().equals(fields) || parser.nextToken() != null) throw LoginRequestReader.invalid();
                return out;
            } catch (JacksonException hidden) {
                throw LoginRequestReader.invalid();
            }
        } finally { Arrays.fill(bytes, (byte) 0); }
    }
}
