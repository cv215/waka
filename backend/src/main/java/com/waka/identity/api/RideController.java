package com.waka.identity.api;

import com.waka.geo.GeoPoint;
import com.waka.identity.session.SessionException;
import com.waka.mobility.ride.PersistentRideService;
import com.waka.mobility.ride.RideException;
import jakarta.servlet.http.HttpServletRequest;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.json.JsonFactory;

/** R13 city rides (K31): presence, search, proposals, the ride itself. No price or position time from the phone. */
@RestController
public final class RideController {
    private static final JsonFactory JSON = new JsonFactory();
    private final ObjectProvider<PersistentRideService> services;
    public RideController(ObjectProvider<PersistentRideService> services) { this.services = services; }

    /** Body {online, latitude?, longitude?}: the position time is the server's. */
    @PutMapping(path = "/api/v1/driver/presence", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PersistentRideService.PresenceView> presence(HttpServletRequest request) throws IOException {
        Map<String, Object> body = read(request, Set.of("online"), Set.of("latitude", "longitude"));
        boolean online = (Boolean) body.get("online");
        GeoPoint position = null;
        if (body.containsKey("latitude") || body.containsKey("longitude")) {
            if (!(body.get("latitude") instanceof BigDecimal lat) || !(body.get("longitude") instanceof BigDecimal lon)) throw LoginRequestReader.invalid();
            try { position = new GeoPoint(lat, lon); } catch (IllegalArgumentException hidden) { throw LoginRequestReader.invalid(); }
        }
        GeoPoint where = position;
        return call(request, (token, client) -> service().presence(token, client, online, where), HttpStatus.OK);
    }

    /** Body {quoteId}; Idempotency-Key. */
    @PostMapping(path = "/api/v1/searches", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PersistentRideService.SearchView> search(HttpServletRequest request) throws IOException {
        UUID key = LoginRequestReader.key(request);
        UUID quote = LoginRequestReader.uuid((String) read(request, Set.of("quoteId"), Set.of()).get("quoteId"));
        char[] token = MeRequestReader.bearer(request);
        try {
            String client = MeRequestReader.client(request);
            var view = invoke(() -> service().search(token, client, key, quote));
            return ResponseEntity.status(view.idempotentReplay() ? HttpStatus.OK : HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(view);
        } finally { Arrays.fill(token, '\0'); }
    }

    @GetMapping(path = "/api/v1/searches/{searchId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PersistentRideService.SearchView> readSearch(@PathVariable String searchId, HttpServletRequest request) throws IOException {
        UUID id = LoginRequestReader.uuid(searchId);
        MeRequestReader.emptyGet(request);
        return call(request, (token, client) -> service().readSearch(token, client, id), HttpStatus.OK);
    }

    @PostMapping(path = "/api/v1/searches/{searchId}/cancellations", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PersistentRideService.SearchView> cancelSearch(@PathVariable String searchId, HttpServletRequest request) throws IOException {
        UUID id = LoginRequestReader.uuid(searchId);
        LoginRequestReader.emptyBody(request);
        return call(request, (token, client) -> service().cancelSearch(token, client, id), HttpStatus.OK);
    }

    @GetMapping(path = "/api/v1/driver/proposals", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<PersistentRideService.ProposalView>> proposals(HttpServletRequest request) throws IOException {
        MeRequestReader.emptyGet(request);
        return call(request, (token, client) -> service().proposals(token, client), HttpStatus.OK);
    }

    /** Body {action: accept|refuse}; 201 with the ride on acceptance, 204 on refusal. */
    @PostMapping(path = "/api/v1/proposals/{proposalId}/responses", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PersistentRideService.RideView> respond(@PathVariable String proposalId, HttpServletRequest request) throws IOException {
        UUID id = LoginRequestReader.uuid(proposalId);
        String action = (String) read(request, Set.of("action"), Set.of()).get("action");
        if (!"accept".equals(action) && !"refuse".equals(action)) throw LoginRequestReader.invalid();
        char[] token = MeRequestReader.bearer(request);
        try {
            String client = MeRequestReader.client(request);
            var ride = invoke(() -> service().respond(token, client, id, "accept".equals(action)));
            return ride == null ? ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build()
                    : ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(ride);
        } finally { Arrays.fill(token, '\0'); }
    }

    @GetMapping(path = "/api/v1/rides/{rideId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PersistentRideService.RideView> ride(@PathVariable String rideId, HttpServletRequest request) throws IOException {
        UUID id = LoginRequestReader.uuid(rideId);
        MeRequestReader.emptyGet(request);
        return call(request, (token, client) -> service().ride(token, client, id), HttpStatus.OK);
    }

    /** Body {action: arrive|board|finish|client_absent|cancel, boardingCode?}. */
    @PostMapping(path = "/api/v1/rides/{rideId}/driver-actions", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PersistentRideService.RideView> driverAction(@PathVariable String rideId, HttpServletRequest request) throws IOException {
        UUID id = LoginRequestReader.uuid(rideId);
        Map<String, Object> body = read(request, Set.of("action"), Set.of("boardingCode"));
        if (!Set.of("arrive", "board", "finish", "client_absent", "cancel").contains(body.get("action"))) throw LoginRequestReader.invalid();
        return call(request, (token, client) -> service().driverAction(token, client, id, (String) body.get("action"),
                (String) body.get("boardingCode")), HttpStatus.OK);
    }

    @PostMapping(path = "/api/v1/rides/{rideId}/cancellations", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PersistentRideService.RideView> cancelByClient(@PathVariable String rideId, HttpServletRequest request) throws IOException {
        UUID id = LoginRequestReader.uuid(rideId);
        LoginRequestReader.emptyBody(request);
        return call(request, (token, client) -> service().cancelByClient(token, client, id), HttpStatus.OK);
    }

    private interface Call<T> { T apply(char[] token, String client); }
    private <T> ResponseEntity<T> call(HttpServletRequest request, Call<T> call, HttpStatus status) {
        char[] token = MeRequestReader.bearer(request);
        try {
            String client = MeRequestReader.client(request);
            return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(invoke(() -> call.apply(token, client)));
        } finally { Arrays.fill(token, '\0'); }
    }

    /** Exact fields: strings, booleans, or plain decimals (at most six decimals) for latitude/longitude. */
    private static Map<String, Object> read(HttpServletRequest request, Set<String> required, Set<String> optional) throws IOException {
        if (request.getQueryString() != null) throw LoginRequestReader.invalid();
        byte[] bytes = request.getInputStream().readNBytes(1025);
        try {
            if (bytes.length > 1024) throw new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE);
            try (JsonParser parser = JSON.createParser(new InputStreamReader(new ByteArrayInputStream(bytes),
                    StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)))) {
                if (parser.nextToken() != JsonToken.START_OBJECT) throw LoginRequestReader.invalid();
                Map<String, Object> fields = new HashMap<>();
                while (parser.nextToken() != JsonToken.END_OBJECT) {
                    String name = parser.getString();
                    JsonToken value = parser.nextToken();
                    if (!(required.contains(name) || optional.contains(name)) || fields.containsKey(name)) throw LoginRequestReader.invalid();
                    if ("latitude".equals(name) || "longitude".equals(name)) {
                        if ((value != JsonToken.VALUE_NUMBER_FLOAT && value != JsonToken.VALUE_NUMBER_INT)
                                || !parser.getText().matches("-?(0|[1-9][0-9]{0,2})(\\.[0-9]{1,6})?")) throw LoginRequestReader.invalid();
                        fields.put(name, new BigDecimal(parser.getText()));
                    } else if ("online".equals(name)) {
                        if (value != JsonToken.VALUE_TRUE && value != JsonToken.VALUE_FALSE) throw LoginRequestReader.invalid();
                        fields.put(name, value == JsonToken.VALUE_TRUE);
                    } else if (value == JsonToken.VALUE_STRING) {
                        fields.put(name, parser.getString());
                    } else {
                        throw LoginRequestReader.invalid();
                    }
                }
                if (!fields.keySet().containsAll(required) || parser.nextToken() != null) throw LoginRequestReader.invalid();
                return fields;
            } catch (JacksonException hidden) {
                throw LoginRequestReader.invalid();
            }
        } finally { Arrays.fill(bytes, (byte) 0); }
    }

    private PersistentRideService service() {
        var service = services.getIfAvailable();
        if (service == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        return service;
    }
    private static <T> T invoke(Supplier<T> operation) {
        try { return operation.get(); }
        catch (SessionException error) {
            throw new ResponseStatusException(error.code() == SessionException.Code.SERVICE_UNAVAILABLE
                    ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.UNAUTHORIZED);
        } catch (RideException error) {
            HttpStatus status = switch (error.code()) {
                case INVALID_REQUEST -> HttpStatus.BAD_REQUEST;
                case NOT_FOUND -> HttpStatus.NOT_FOUND;
                case DRIVER_NOT_ALLOWED -> HttpStatus.FORBIDDEN;
                case QUOTE_EXPIRED, PROPOSAL_EXPIRED, CAR_BUSY, NOT_PAID, NOT_BOARDED, NOT_ARRIVED, ALREADY_BOARDED -> HttpStatus.CONFLICT;
                case WRONG_CODE, TOO_EARLY -> HttpStatus.UNPROCESSABLE_CONTENT;
                case SERVICE_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            };
            throw new ProfileHttpException(status, error.code() == RideException.Code.NOT_FOUND ? "RESOURCE_NOT_FOUND" : error.code().name());
        }
    }
}
