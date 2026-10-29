package com.waka.identity.api;

import com.waka.identity.session.SessionException;
import com.waka.mobility.voyage.PersistentVoyageService;
import com.waka.mobility.voyage.VoyageException;
import com.waka.mobility.voyage.VoyageRules;
import jakarta.servlet.http.HttpServletRequest;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.json.JsonFactory;

/** R11 intercity listings (K25). No driver identity, reservation or payment is exposed here. */
@RestController
public final class VoyageController {
    record PublishInput(List<PersistentVoyageService.Stop> stops, int seats, Map<String, Integer> prices) {
        @Override public String toString() { return "VoyageInput[REDACTED]"; }
    }
    private static final JsonFactory JSON = new JsonFactory();
    private static final int MAX_BODY = 4096;
    private final ObjectProvider<PersistentVoyageService> services;
    public VoyageController(ObjectProvider<PersistentVoyageService> services) { this.services = services; }

    @PostMapping(path = "/api/v1/voyages", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PersistentVoyageService.VoyageView> publish(HttpServletRequest request) throws IOException {
        UUID key = LoginRequestReader.key(request);
        PublishInput input = read(request);
        char[] token = MeRequestReader.bearer(request);
        try {
            String client = MeRequestReader.client(request);
            var voyage = invoke(() -> service().publish(token, client, key, input.stops(), input.seats(), input.prices()));
            return ResponseEntity.status(voyage.idempotentReplay() ? HttpStatus.OK : HttpStatus.CREATED)
                    .cacheControl(CacheControl.noStore()).body(voyage);
        } finally { Arrays.fill(token, '\0'); }
    }

    @GetMapping(path = "/api/v1/voyages", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<PersistentVoyageService.VoyageView>> upcoming(HttpServletRequest request) throws IOException {
        MeRequestReader.emptyGet(request);
        char[] token = MeRequestReader.bearer(request);
        try {
            String client = MeRequestReader.client(request);
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(invoke(() -> service().upcoming(token, client)));
        } finally { Arrays.fill(token, '\0'); }
    }

    @GetMapping(path = "/api/v1/driver/voyages", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<PersistentVoyageService.VoyageView>> mine(HttpServletRequest request) throws IOException {
        MeRequestReader.emptyGet(request);
        char[] token = MeRequestReader.bearer(request);
        try {
            String client = MeRequestReader.client(request);
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(invoke(() -> service().mine(token, client)));
        } finally { Arrays.fill(token, '\0'); }
    }

    @PostMapping(path = "/api/v1/voyages/{voyageId}/cancellations", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PersistentVoyageService.VoyageView> cancel(@PathVariable String voyageId, HttpServletRequest request)
            throws IOException {
        UUID id = LoginRequestReader.uuid(voyageId);
        String reason = LoginRequestReader.body(request, Set.of("reasonCode")).get("reasonCode");
        char[] token = MeRequestReader.bearer(request);
        try {
            String client = MeRequestReader.client(request);
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(invoke(() -> service().cancel(token, client, id, reason)));
        } finally { Arrays.fill(token, '\0'); }
    }

    /** The driver's own voyage only; no passenger identity or code is ever returned. */
    @GetMapping(path = "/api/v1/voyages/{voyageId}/passengers", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<PersistentVoyageService.PassengerView>> passengers(@PathVariable String voyageId, HttpServletRequest request)
            throws IOException {
        UUID id = LoginRequestReader.uuid(voyageId);
        MeRequestReader.emptyGet(request);
        char[] token = MeRequestReader.bearer(request);
        try {
            String client = MeRequestReader.client(request);
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(invoke(() -> service().passengers(token, client, id)));
        } finally { Arrays.fill(token, '\0'); }
    }

    /** Body {boardingCode}: six digits given by the passenger. Naturally idempotent for the same code. */
    @PostMapping(path = "/api/v1/voyages/{voyageId}/boardings", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PersistentVoyageService.PassengerView> board(@PathVariable String voyageId, HttpServletRequest request)
            throws IOException {
        UUID id = LoginRequestReader.uuid(voyageId);
        String code = LoginRequestReader.body(request, Set.of("boardingCode")).get("boardingCode");
        if (!code.matches("[0-9]{6}")) throw LoginRequestReader.invalid();
        char[] token = MeRequestReader.bearer(request);
        try {
            String client = MeRequestReader.client(request);
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(invoke(() -> service().board(token, client, id, code)));
        } finally { Arrays.fill(token, '\0'); }
    }
    /** R12: body {reservationId}; the driver declares a paid passenger absent after the boarding time. */
    @PostMapping(path = "/api/v1/voyages/{voyageId}/no-shows", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PersistentVoyageService.PassengerView> noShow(@PathVariable String voyageId, HttpServletRequest request)
            throws IOException {
        UUID id = LoginRequestReader.uuid(voyageId);
        UUID reservation = LoginRequestReader.uuid(LoginRequestReader.body(request, Set.of("reservationId")).get("reservationId"));
        char[] token = MeRequestReader.bearer(request);
        try {
            String client = MeRequestReader.client(request);
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(invoke(() -> service().markNoShow(token, client, id, reservation)));
        } finally { Arrays.fill(token, '\0'); }
    }

    /** Exactly {fromIndex, toIndex, seats}: the price comes from the driver's list, never from the client. */
    @PostMapping(path = "/api/v1/voyages/{voyageId}/reservations", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PersistentVoyageService.ReservationView> reserve(@PathVariable String voyageId, HttpServletRequest request)
            throws IOException {
        UUID id = LoginRequestReader.uuid(voyageId);
        UUID key = LoginRequestReader.key(request);
        int[] input = reservation(request);
        char[] token = MeRequestReader.bearer(request);
        try {
            String client = MeRequestReader.client(request);
            var reservation = invoke(() -> service().reserve(token, client, key, id, input[0], input[1], input[2]));
            return ResponseEntity.status(reservation.idempotentReplay() ? HttpStatus.OK : HttpStatus.CREATED)
                    .cacheControl(CacheControl.noStore()).body(reservation);
        } finally { Arrays.fill(token, '\0'); }
    }

    @GetMapping(path = "/api/v1/me/voyage-reservations", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<PersistentVoyageService.ReservationView>> myReservations(HttpServletRequest request) throws IOException {
        MeRequestReader.emptyGet(request);
        char[] token = MeRequestReader.bearer(request);
        try {
            String client = MeRequestReader.client(request);
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(invoke(() -> service().myReservations(token, client)));
        } finally { Arrays.fill(token, '\0'); }
    }

    /** Naturally idempotent: a second cancellation returns the reservation unchanged. */
    @PostMapping(path = "/api/v1/voyage-reservations/{reservationId}/cancellations", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PersistentVoyageService.ReservationView> cancelReservation(@PathVariable String reservationId,
            HttpServletRequest request) throws IOException {
        UUID id = LoginRequestReader.uuid(reservationId);
        LoginRequestReader.emptyBody(request);
        char[] token = MeRequestReader.bearer(request);
        try {
            String client = MeRequestReader.client(request);
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(invoke(() -> service().cancelReservation(token, client, id)));
        } finally { Arrays.fill(token, '\0'); }
    }

    private static int[] reservation(HttpServletRequest request) throws IOException {
        if (request.getQueryString() != null) throw LoginRequestReader.invalid();
        byte[] body = request.getInputStream().readNBytes(257);
        try {
            if (body.length > 256) throw new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE);
            try (JsonParser parser = JSON.createParser(new InputStreamReader(new ByteArrayInputStream(body),
                    StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)))) {
                if (parser.nextToken() != JsonToken.START_OBJECT) throw LoginRequestReader.invalid();
                Map<String, Integer> fields = new HashMap<>();
                while (parser.nextToken() != JsonToken.END_OBJECT) {
                    String name = parser.getString();
                    if (!Set.of("fromIndex", "toIndex", "seats").contains(name) || fields.containsKey(name)
                            || parser.nextToken() != JsonToken.VALUE_NUMBER_INT || !parser.getText().matches("[0-5]")) {
                        throw LoginRequestReader.invalid();
                    }
                    fields.put(name, Integer.parseInt(parser.getText()));
                }
                if (fields.size() != 3 || parser.nextToken() != null) throw LoginRequestReader.invalid();
                return new int[] { fields.get("fromIndex"), fields.get("toIndex"), fields.get("seats") };
            } catch (JacksonException hidden) {
                throw LoginRequestReader.invalid();
            }
        } finally { Arrays.fill(body, (byte) 0); }
    }

    /** Exactly {stops:[{place, plannedAt}], seats, prices:{"0-1": int, ...}}; plannedAt is an ISO instant in UTC. */
    static PublishInput read(HttpServletRequest request) throws IOException {
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
                List<PersistentVoyageService.Stop> stops = null;
                Integer seats = null;
                Map<String, Integer> prices = null;
                while (parser.nextToken() != JsonToken.END_OBJECT) {
                    if (parser.currentToken() != JsonToken.PROPERTY_NAME) throw LoginRequestReader.invalid();
                    String name = parser.getString();
                    JsonToken value = parser.nextToken();
                    switch (name) {
                        case "stops" -> {
                            if (stops != null || value != JsonToken.START_ARRAY) throw LoginRequestReader.invalid();
                            stops = stops(parser);
                        }
                        case "seats" -> {
                            if (seats != null || value != JsonToken.VALUE_NUMBER_INT || !parser.getText().matches("[1-5]")) throw LoginRequestReader.invalid();
                            seats = Integer.parseInt(parser.getText());
                        }
                        case "prices" -> {
                            if (prices != null || value != JsonToken.START_OBJECT) throw LoginRequestReader.invalid();
                            prices = prices(parser);
                        }
                        default -> throw LoginRequestReader.invalid();
                    }
                }
                if (stops == null || seats == null || prices == null || parser.nextToken() != null) throw LoginRequestReader.invalid();
                return new PublishInput(stops, seats, prices);
            } catch (JacksonException | IllegalArgumentException | DateTimeParseException | IOException hidden) {
                throw LoginRequestReader.invalid();
            }
        } finally { Arrays.fill(body, (byte) 0); }
    }

    private static List<PersistentVoyageService.Stop> stops(JsonParser parser) {
        List<PersistentVoyageService.Stop> stops = new ArrayList<>();
        while (parser.nextToken() != JsonToken.END_ARRAY) {
            if (parser.currentToken() != JsonToken.START_OBJECT || stops.size() >= 6) throw LoginRequestReader.invalid();
            Map<String, String> item = new HashMap<>();
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                String name = parser.getString();
                if (!Set.of("place", "plannedAt").contains(name) || item.containsKey(name) || parser.nextToken() != JsonToken.VALUE_STRING) {
                    throw LoginRequestReader.invalid();
                }
                item.put(name, parser.getString());
            }
            if (item.size() != 2 || !item.get("plannedAt").matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}(:\\d{2})?Z")) {
                throw LoginRequestReader.invalid();
            }
            stops.add(new PersistentVoyageService.Stop(item.get("place"), Instant.parse(item.get("plannedAt"))));
        }
        return stops;
    }

    private static Map<String, Integer> prices(JsonParser parser) {
        Map<String, Integer> prices = new HashMap<>();
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            String pair = parser.getString();
            if (!pair.matches("[0-5]-[0-5]") || prices.containsKey(pair) || parser.nextToken() != JsonToken.VALUE_NUMBER_INT
                    || !parser.getText().matches("[1-9][0-9]{0,4}") || prices.size() >= 15) {
                throw LoginRequestReader.invalid();
            }
            int price = Integer.parseInt(parser.getText());
            if (price > VoyageRules.MAX_SEAT_PRICE_XAF) throw LoginRequestReader.invalid();
            prices.put(pair, price);
        }
        return prices;
    }

    private PersistentVoyageService service() {
        var service = services.getIfAvailable();
        if (service == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        return service;
    }
    private static <T> T invoke(Supplier<T> operation) {
        try { return operation.get(); }
        catch (SessionException error) {
            throw new ResponseStatusException(error.code() == SessionException.Code.SERVICE_UNAVAILABLE
                    ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.UNAUTHORIZED);
        } catch (VoyageException error) {
            HttpStatus status = switch (error.code()) {
                case INVALID_REQUEST -> HttpStatus.BAD_REQUEST;
                case DRIVER_NOT_ALLOWED -> HttpStatus.FORBIDDEN;
                case TOO_MANY_SEATS, OWN_VOYAGE, DEPARTED -> HttpStatus.UNPROCESSABLE_CONTENT;
                case NOT_ENOUGH_SEATS, ALREADY_BOARDED, NOT_PAID -> HttpStatus.CONFLICT;
                case WRONG_CODE, TOO_EARLY -> HttpStatus.UNPROCESSABLE_CONTENT;
                case BOARDING_LOCKED -> HttpStatus.TOO_MANY_REQUESTS;
                case NOT_FOUND -> HttpStatus.NOT_FOUND;
                case IDEMPOTENCY_KEY_REUSED -> HttpStatus.CONFLICT;
                case SERVICE_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            };
            throw new ProfileHttpException(status, error.code() == VoyageException.Code.NOT_FOUND ? "RESOURCE_NOT_FOUND" : error.code().name());
        }
    }
}
