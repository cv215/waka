package com.waka.identity.api;

import com.waka.identity.privacy.PersistentPrivacyService;
import com.waka.identity.privacy.PrivacyException;
import com.waka.identity.session.SessionException;
import jakarta.servlet.http.HttpServletRequest;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.json.JsonFactory;

/** A06 and A07 (K21): only the bearer's own account; the details text is never returned. */
@RestController
public final class PrivacyController {
    record RequestInput(PersistentPrivacyService.Kind kind, String details) {
        @Override public String toString() { return "PrivacyRequestInput[REDACTED]"; }
    }
    private static final JsonFactory JSON = new JsonFactory();
    private static final int MAX_BODY = 4096;
    private final ObjectProvider<PersistentPrivacyService> services;
    public PrivacyController(ObjectProvider<PersistentPrivacyService> services) { this.services = services; }

    @PostMapping(path = "/api/v1/me/privacy-requests", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PersistentPrivacyService.RequestView> request(HttpServletRequest request) throws IOException {
        UUID key = LoginRequestReader.key(request);
        RequestInput input = read(request);
        char[] token = MeRequestReader.bearer(request);
        try {
            String client = MeRequestReader.client(request);
            var view = invoke(() -> service().request(token, client, key, input.kind(), input.details()));
            return ResponseEntity.status(view.created() ? HttpStatus.CREATED : HttpStatus.OK)
                    .cacheControl(CacheControl.noStore()).body(view);
        } finally { Arrays.fill(token, '\0'); }
    }

    @GetMapping(path = "/api/v1/me/activity", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PersistentPrivacyService.ActivityPage> activity(HttpServletRequest request) throws IOException {
        Map<String, String> query = new HashMap<>();
        String raw = request.getQueryString();
        if (raw != null) {
            if (raw.length() > 128) throw LoginRequestReader.invalid();
            for (String pair : raw.split("&", -1)) {
                int equals = pair.indexOf('=');
                if (equals <= 0) throw LoginRequestReader.invalid();
                String name = pair.substring(0, equals), value = pair.substring(equals + 1);
                if (!(name.equals("cursor") || name.equals("limit")) || query.containsKey(name)
                        || !value.matches("[0-9a-f_-]{1,64}")) throw LoginRequestReader.invalid();
                query.put(name, value);
            }
        }
        if (request.getInputStream().read() != -1) throw LoginRequestReader.invalid();
        String limitText = query.getOrDefault("limit", "20");
        if (!limitText.matches("[1-9][0-9]?")) throw LoginRequestReader.invalid();
        int limit = Integer.parseInt(limitText);
        char[] token = MeRequestReader.bearer(request);
        try {
            String client = MeRequestReader.client(request);
            return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                    .body(invoke(() -> service().activity(token, client, query.get("cursor"), limit)));
        } finally { Arrays.fill(token, '\0'); }
    }

    /** Exactly {kind} or {kind, details}; details may be null. */
    static RequestInput read(HttpServletRequest request) throws IOException {
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
                PersistentPrivacyService.Kind kind = null;
                String details = null;
                boolean seenDetails = false;
                while (parser.nextToken() != JsonToken.END_OBJECT) {
                    if (parser.currentToken() != JsonToken.PROPERTY_NAME) throw LoginRequestReader.invalid();
                    String name = parser.getString();
                    JsonToken value = parser.nextToken();
                    if ("kind".equals(name) && kind == null && value == JsonToken.VALUE_STRING) {
                        kind = switch (parser.getString()) {
                            case "access" -> PersistentPrivacyService.Kind.ACCESS;
                            case "deletion" -> PersistentPrivacyService.Kind.DELETION;
                            default -> throw LoginRequestReader.invalid();
                        };
                    } else if ("details".equals(name) && !seenDetails
                            && (value == JsonToken.VALUE_STRING || value == JsonToken.VALUE_NULL)) {
                        seenDetails = true;
                        details = value == JsonToken.VALUE_NULL ? null : parser.getString();
                    } else {
                        throw LoginRequestReader.invalid();
                    }
                }
                if (kind == null || parser.nextToken() != null) throw LoginRequestReader.invalid();
                return new RequestInput(kind, details);
            } catch (JacksonException | IllegalArgumentException | IOException hidden) {
                throw LoginRequestReader.invalid();
            }
        } finally { Arrays.fill(body, (byte) 0); }
    }

    private PersistentPrivacyService service() {
        var service = services.getIfAvailable();
        if (service == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        return service;
    }
    private static <T> T invoke(Supplier<T> operation) {
        try { return operation.get(); }
        catch (SessionException error) {
            throw new ResponseStatusException(error.code() == SessionException.Code.SERVICE_UNAVAILABLE
                    ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.UNAUTHORIZED);
        } catch (PrivacyException error) {
            HttpStatus status = switch (error.code()) {
                case INVALID_REQUEST -> HttpStatus.BAD_REQUEST;
                case IDEMPOTENCY_KEY_REUSED -> HttpStatus.CONFLICT;
                case SERVICE_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            };
            throw new ProfileHttpException(status, error.code().name());
        }
    }
}
