package com.waka.identity.api;

import com.waka.identity.session.SessionException;
import com.waka.mobility.incident.IncidentException;
import com.waka.mobility.incident.PersistentIncidentService;
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

/** A23-A25 for the two parties, A39 for ASSISTANCE (K30). Identities are never returned, only roles. */
@RestController
public final class IncidentController {
    private static final JsonFactory JSON = new JsonFactory();
    private final ObjectProvider<PersistentIncidentService> services;
    public IncidentController(ObjectProvider<PersistentIncidentService> services) { this.services = services; }

    /** Body {reservationId, category, description}. */
    @PostMapping(path = "/api/v1/incidents", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PersistentIncidentService.IncidentView> report(HttpServletRequest request) throws IOException {
        UUID key = LoginRequestReader.key(request);
        Map<String, Object> body = read(request, Set.of("reservationId", "category", "description"), Set.of());
        UUID reservation = LoginRequestReader.uuid((String) body.get("reservationId"));
        if (!PersistentIncidentService.CATEGORIES.contains(body.get("category"))) throw LoginRequestReader.invalid();
        char[] token = MeRequestReader.bearer(request);
        try {
            String client = MeRequestReader.client(request);
            var view = invoke(() -> service().report(token, client, key, reservation, (String) body.get("category"), (String) body.get("description")));
            return ResponseEntity.status(view.idempotentReplay() ? HttpStatus.OK : HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(view);
        } finally { Arrays.fill(token, '\0'); }
    }

    @GetMapping(path = "/api/v1/me/incidents", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<PersistentIncidentService.IncidentView>> mine(HttpServletRequest request) throws IOException {
        MeRequestReader.emptyGet(request);
        char[] token = MeRequestReader.bearer(request);
        try {
            String client = MeRequestReader.client(request);
            return ok(invoke(() -> service().mine(token, client)));
        } finally { Arrays.fill(token, '\0'); }
    }

    /** Body {kind: reply|contest, text}. */
    @PostMapping(path = "/api/v1/incidents/{incidentId}/messages", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PersistentIncidentService.IncidentView> message(@PathVariable String incidentId, HttpServletRequest request)
            throws IOException {
        UUID id = LoginRequestReader.uuid(incidentId);
        UUID key = LoginRequestReader.key(request);
        Map<String, Object> body = read(request, Set.of("kind", "text"), Set.of());
        if (!Set.of("reply", "contest").contains(body.get("kind"))) throw LoginRequestReader.invalid();
        char[] token = MeRequestReader.bearer(request);
        try {
            String client = MeRequestReader.client(request);
            var view = invoke(() -> service().message(token, client, key, id, (String) body.get("kind"), (String) body.get("text")));
            return ResponseEntity.status(view.idempotentReplay() ? HttpStatus.OK : HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(view);
        } finally { Arrays.fill(token, '\0'); }
    }

    @GetMapping(path = "/api/v1/admin/incidents", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<PersistentIncidentService.IncidentView>> queue(HttpServletRequest request) throws IOException {
        MeRequestReader.emptyGet(request);
        char[] token = MeRequestReader.bearer(request), adminToken = AdminAccessController.adminToken(request);
        try {
            String client = MeRequestReader.client(request);
            return ok(invoke(() -> service().queue(token, client, adminToken)));
        } finally { Arrays.fill(token, '\0'); Arrays.fill(adminToken, '\0'); }
    }

    /** Body {expectedVersion, decision, reasonCode, text?}. */
    @PostMapping(path = "/api/v1/admin/incidents/{incidentId}/decisions", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PersistentIncidentService.IncidentView> decide(@PathVariable String incidentId, HttpServletRequest request)
            throws IOException {
        UUID id = LoginRequestReader.uuid(incidentId);
        UUID key = LoginRequestReader.key(request);
        Map<String, Object> body = read(request, Set.of("expectedVersion", "decision", "reasonCode"), Set.of("text"));
        if (!PersistentIncidentService.DECISIONS.contains(body.get("decision"))) throw LoginRequestReader.invalid();
        char[] token = MeRequestReader.bearer(request), adminToken = AdminAccessController.adminToken(request);
        try {
            String client = MeRequestReader.client(request);
            var view = invoke(() -> service().decide(token, client, adminToken, key, id, (Long) body.get("expectedVersion"),
                    (String) body.get("decision"), (String) body.get("reasonCode"), (String) body.get("text")));
            return ResponseEntity.status(view.idempotentReplay() ? HttpStatus.OK : HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(view);
        } finally { Arrays.fill(token, '\0'); Arrays.fill(adminToken, '\0'); }
    }

    /** Exact fields: strings, except expectedVersion (positive integer); optional fields may be null. */
    private static Map<String, Object> read(HttpServletRequest request, Set<String> required, Set<String> optional) throws IOException {
        if (request.getQueryString() != null) throw LoginRequestReader.invalid();
        byte[] bytes = request.getInputStream().readNBytes(8193);
        try {
            if (bytes.length > 8192) throw new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE);
            try (JsonParser parser = JSON.createParser(new InputStreamReader(new ByteArrayInputStream(bytes),
                    StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)))) {
                if (parser.nextToken() != JsonToken.START_OBJECT) throw LoginRequestReader.invalid();
                Map<String, Object> fields = new HashMap<>();
                while (parser.nextToken() != JsonToken.END_OBJECT) {
                    String name = parser.getString();
                    JsonToken value = parser.nextToken();
                    if (!(required.contains(name) || optional.contains(name)) || fields.containsKey(name)) throw LoginRequestReader.invalid();
                    if ("expectedVersion".equals(name)) {
                        if (value != JsonToken.VALUE_NUMBER_INT || !parser.getText().matches("[1-9][0-9]{0,15}")) throw LoginRequestReader.invalid();
                        fields.put(name, Long.parseLong(parser.getText()));
                    } else if (value == JsonToken.VALUE_STRING) {
                        fields.put(name, parser.getString());
                    } else if (value == JsonToken.VALUE_NULL && optional.contains(name)) {
                        fields.put(name, null);
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

    private PersistentIncidentService service() {
        var service = services.getIfAvailable();
        if (service == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        return service;
    }
    private static <T> ResponseEntity<T> ok(T body) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body); }
    private static <T> T invoke(Supplier<T> operation) {
        try { return operation.get(); }
        catch (SessionException error) {
            throw new ResponseStatusException(error.code() == SessionException.Code.SERVICE_UNAVAILABLE
                    ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.UNAUTHORIZED);
        } catch (IncidentException error) {
            HttpStatus status = switch (error.code()) {
                case INVALID_REQUEST -> HttpStatus.BAD_REQUEST;
                case NOT_FOUND -> HttpStatus.NOT_FOUND;
                case NOT_DECIDED, IDEMPOTENCY_KEY_REUSED, VERSION_CONFLICT -> HttpStatus.CONFLICT;
                case CONFLICT_OF_INTEREST, NOT_AUTHORIZED -> HttpStatus.FORBIDDEN;
                case INVALID_ADMIN_SESSION -> HttpStatus.UNAUTHORIZED;
                case SERVICE_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            };
            String code = switch (error.code()) {
                case NOT_FOUND -> "RESOURCE_NOT_FOUND";
                case NOT_AUTHORIZED -> "ACTION_NOT_ALLOWED";
                default -> error.code().name();
            };
            throw new ProfileHttpException(status, code);
        }
    }
}
