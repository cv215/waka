package com.waka.identity.api;

import com.waka.geo.GeoPoint;
import com.waka.identity.session.SessionException;
import com.waka.mobility.quote.PersistentQuoteService;
import com.waka.mobility.quote.QuoteException;
import jakarta.servlet.http.HttpServletRequest;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
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

/** A08 (K20): no price, distance, fare version or band is ever accepted from the client. */
@RestController
public final class QuoteController {
    record QuoteInput(PersistentQuoteService.Mode mode, int passengerCount, GeoPoint origin, GeoPoint destination) {
        @Override public String toString() { return "QuoteInput[REDACTED]"; }
    }
    private static final JsonFactory JSON = new JsonFactory();
    private static final int MAX_BODY = 1024;
    private final ObjectProvider<PersistentQuoteService> services;
    public QuoteController(ObjectProvider<PersistentQuoteService> services) { this.services = services; }

    @PostMapping(path = "/api/v1/quotes", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PersistentQuoteService.QuoteView> create(HttpServletRequest request) throws IOException {
        UUID key = LoginRequestReader.key(request);
        QuoteInput input = read(request);
        char[] token = MeRequestReader.bearer(request);
        try {
            String client = MeRequestReader.client(request);
            var quote = invoke(() -> service().create(token, client, key, input.mode(), input.passengerCount(),
                    input.origin(), input.destination()));
            return ResponseEntity.status(quote.idempotentReplay() ? HttpStatus.OK : HttpStatus.CREATED)
                    .cacheControl(CacheControl.noStore()).body(quote);
        } finally { Arrays.fill(token, '\0'); }
    }

    /** Exactly {mode, passengerCount, origin:{latitude, longitude}, destination:{latitude, longitude}}. */
    static QuoteInput read(HttpServletRequest request) throws IOException {
        if (request.getQueryString() != null) throw LoginRequestReader.invalid();
        String encoding = request.getHeader("Content-Encoding");
        if (encoding != null && !"identity".equalsIgnoreCase(encoding)) throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        byte[] body = request.getInputStream().readNBytes(MAX_BODY + 1);
        try {
            if (body.length > MAX_BODY) throw new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE);
            var decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            try (var reader = new InputStreamReader(new ByteArrayInputStream(body), decoder);
                    JsonParser parser = JSON.createParser(reader)) {
                if (parser.nextToken() != JsonToken.START_OBJECT) throw LoginRequestReader.invalid();
                Map<String, Object> fields = new HashMap<>();
                while (parser.nextToken() != JsonToken.END_OBJECT) {
                    if (parser.currentToken() != JsonToken.PROPERTY_NAME) throw LoginRequestReader.invalid();
                    String name = parser.getString();
                    JsonToken value = parser.nextToken();
                    if (fields.containsKey(name)) throw LoginRequestReader.invalid();
                    switch (name) {
                        case "mode" -> {
                            if (value != JsonToken.VALUE_STRING) throw LoginRequestReader.invalid();
                            fields.put(name, switch (parser.getString()) {
                                case "shared" -> PersistentQuoteService.Mode.SHARED;
                                case "vip" -> PersistentQuoteService.Mode.VIP;
                                default -> throw LoginRequestReader.invalid();
                            });
                        }
                        case "passengerCount" -> {
                            if (value != JsonToken.VALUE_NUMBER_INT || !parser.getText().matches("[1-5]")) throw LoginRequestReader.invalid();
                            fields.put(name, Integer.parseInt(parser.getText()));
                        }
                        case "origin", "destination" -> {
                            if (value != JsonToken.START_OBJECT) throw LoginRequestReader.invalid();
                            fields.put(name, point(parser));
                        }
                        default -> throw LoginRequestReader.invalid();
                    }
                }
                if (!fields.keySet().equals(Set.of("mode", "passengerCount", "origin", "destination")) || parser.nextToken() != null) {
                    throw LoginRequestReader.invalid();
                }
                return new QuoteInput((PersistentQuoteService.Mode) fields.get("mode"), (Integer) fields.get("passengerCount"),
                        (GeoPoint) fields.get("origin"), (GeoPoint) fields.get("destination"));
            } catch (JacksonException | IllegalArgumentException | ClassCastException | IOException hidden) {
                throw LoginRequestReader.invalid();
            }
        } finally { Arrays.fill(body, (byte) 0); }
    }

    /** Plain decimal numbers only: no exponent, no string, at most six decimals. */
    private static GeoPoint point(JsonParser parser) {
        Map<String, BigDecimal> values = new HashMap<>();
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            String name = parser.getString();
            JsonToken value = parser.nextToken();
            if (!Set.of("latitude", "longitude").contains(name) || values.containsKey(name)
                    || (value != JsonToken.VALUE_NUMBER_FLOAT && value != JsonToken.VALUE_NUMBER_INT)
                    || !parser.getText().matches("-?(0|[1-9][0-9]{0,2})(\\.[0-9]{1,6})?")) {
                throw LoginRequestReader.invalid();
            }
            values.put(name, new BigDecimal(parser.getText()));
        }
        if (values.size() != 2) throw LoginRequestReader.invalid();
        return new GeoPoint(values.get("latitude"), values.get("longitude"));
    }

    private PersistentQuoteService service() {
        var service = services.getIfAvailable();
        if (service == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        return service;
    }
    private static <T> T invoke(Supplier<T> operation) {
        try { return operation.get(); }
        catch (SessionException error) {
            throw new ResponseStatusException(error.code() == SessionException.Code.SERVICE_UNAVAILABLE
                    ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.UNAUTHORIZED);
        } catch (QuoteException error) {
            HttpStatus status = switch (error.code()) {
                case INVALID_REQUEST -> HttpStatus.BAD_REQUEST;
                case OUTSIDE_SERVICE_AREA, NO_ROUTE, DISTANCE_OUT_OF_RANGE -> HttpStatus.UNPROCESSABLE_CONTENT;
                case IDEMPOTENCY_KEY_REUSED -> HttpStatus.CONFLICT;
                case ROUTE_UNAVAILABLE, SERVICE_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            };
            throw new ProfileHttpException(status, error.code().name());
        }
    }
}
