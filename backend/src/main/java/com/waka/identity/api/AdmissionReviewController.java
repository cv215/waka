package com.waka.identity.api;

import com.waka.identity.admission.AdmissionException;
import com.waka.identity.admission.AdmissionReviewService;
import com.waka.identity.session.SessionException;
import jakarta.servlet.http.HttpServletRequest;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
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

/** 06.08.a: A37 (admission only) and A38 (request_correction / refuse) for an ADMISSION agent. */
@RestController
public final class AdmissionReviewController {
    record DecisionInput(long expectedVersion, String action, String reasonCode, String details,
            List<AdmissionReviewService.PhotoReview> photoDecisions, List<AdmissionReviewService.DocumentReview> documentDecisions) {
        @Override public String toString() { return "DecisionInput[REDACTED]"; }
    }
    private static final JsonFactory JSON = new JsonFactory();
    private final ObjectProvider<AdmissionReviewService> services;
    public AdmissionReviewController(ObjectProvider<AdmissionReviewService> services) { this.services = services; }

    @GetMapping(path = "/api/v1/admin/work-items", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<AdmissionReviewService.WorkItem>> workItems(HttpServletRequest request) throws IOException {
        // Only category=admission exists in this point; the scope is never chosen freely by the screen.
        if (!"category=admission".equals(request.getQueryString())) throw LoginRequestReader.invalid();
        LoginRequestReader.singleHeader(request, "Authorization");
        if (request.getInputStream().read() != -1) throw LoginRequestReader.invalid();
        char[] token = MeRequestReader.bearer(request);
        char[] adminToken = AdminAccessController.adminToken(request);
        try {
            String client = MeRequestReader.client(request);
            return ok(invoke(() -> service().workItems(token, client, adminToken)));
        } finally { Arrays.fill(token, '\0'); Arrays.fill(adminToken, '\0'); }
    }

    @GetMapping(path = "/api/v1/admin/driver-applications/{applicationId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AdmissionReviewService.Detail> detail(@PathVariable String applicationId,
            HttpServletRequest request) throws IOException {
        UUID id = LoginRequestReader.uuid(applicationId);
        MeRequestReader.emptyGet(request);
        char[] token = MeRequestReader.bearer(request);
        char[] adminToken = AdminAccessController.adminToken(request);
        try {
            String client = MeRequestReader.client(request);
            return ok(invoke(() -> service().detail(token, client, adminToken, id)));
        } finally { Arrays.fill(token, '\0'); Arrays.fill(adminToken, '\0'); }
    }

    @PostMapping(path = "/api/v1/admin/driver-applications/{applicationId}/decisions",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AdmissionReviewService.Decision> decide(@PathVariable String applicationId,
            HttpServletRequest request) throws IOException {
        UUID id = LoginRequestReader.uuid(applicationId);
        UUID key = LoginRequestReader.key(request);
        DecisionInput input = read(request);
        char[] token = MeRequestReader.bearer(request);
        char[] adminToken = AdminAccessController.adminToken(request);
        try {
            String client = MeRequestReader.client(request);
            var decision = invoke(() -> service().decide(token, client, adminToken, id, key, input.expectedVersion(),
                    input.action(), input.reasonCode(), input.details(), input.photoDecisions(), input.documentDecisions()));
            return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(decision);
        } finally { Arrays.fill(token, '\0'); Arrays.fill(adminToken, '\0'); }
    }

    /** Exact fields; any approval flag, state or document reference is refused here. */
    static DecisionInput read(HttpServletRequest request) throws IOException {
        if (request.getQueryString() != null) throw LoginRequestReader.invalid();
        if (request.getHeader("Content-Encoding") != null && !"identity".equalsIgnoreCase(request.getHeader("Content-Encoding"))) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        }
        byte[] body = request.getInputStream().readNBytes(8193);
        try {
            if (body.length > 8192) throw new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE);
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
                        case "expectedVersion" -> {
                            if (value != JsonToken.VALUE_NUMBER_INT || !parser.getText().matches("[1-9][0-9]{0,15}")) throw LoginRequestReader.invalid();
                            fields.put(name, Long.parseLong(parser.getText()));
                        }
                        case "action", "reasonCode" -> {
                            if (value != JsonToken.VALUE_STRING) throw LoginRequestReader.invalid();
                            fields.put(name, parser.getString());
                        }
                        case "details" -> {
                            if (value != JsonToken.VALUE_STRING && value != JsonToken.VALUE_NULL) throw LoginRequestReader.invalid();
                            fields.put(name, value == JsonToken.VALUE_NULL ? null : parser.getString());
                        }
                        case "photoDecisions" -> {
                            if (value != JsonToken.START_ARRAY) throw LoginRequestReader.invalid();
                            fields.put(name, photoDecisions(parser));
                        }
                        case "documentDecisions" -> {
                            if (value != JsonToken.START_ARRAY) throw LoginRequestReader.invalid();
                            fields.put(name, documentDecisions(parser));
                        }
                        default -> throw LoginRequestReader.invalid();
                    }
                }
                if (!fields.keySet().containsAll(Set.of("expectedVersion", "action", "reasonCode")) || parser.nextToken() != null) {
                    throw LoginRequestReader.invalid();
                }
                @SuppressWarnings("unchecked")
                var photos = (List<AdmissionReviewService.PhotoReview>) fields.getOrDefault("photoDecisions", List.of());
                @SuppressWarnings("unchecked")
                var documents = (List<AdmissionReviewService.DocumentReview>) fields.getOrDefault("documentDecisions", List.of());
                return new DecisionInput((Long) fields.get("expectedVersion"), (String) fields.get("action"),
                        (String) fields.get("reasonCode"), (String) fields.get("details"), photos, documents);
            } catch (JacksonException | IllegalArgumentException | ClassCastException | IOException hidden) {
                throw LoginRequestReader.invalid();
            }
        } finally { Arrays.fill(body, (byte) 0); }
    }

    private static List<AdmissionReviewService.PhotoReview> photoDecisions(JsonParser parser) {
        List<AdmissionReviewService.PhotoReview> reviews = new ArrayList<>();
        while (parser.nextToken() != JsonToken.END_ARRAY) {
            if (parser.currentToken() != JsonToken.START_OBJECT || reviews.size() >= 6) throw LoginRequestReader.invalid();
            Map<String, String> item = new HashMap<>();
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                String name = parser.getString();
                JsonToken value = parser.nextToken();
                if (!Set.of("view", "fileId", "result", "reasonCode").contains(name) || item.containsKey(name)) throw LoginRequestReader.invalid();
                if (value == JsonToken.VALUE_NULL && "reasonCode".equals(name)) item.put(name, null);
                else if (value == JsonToken.VALUE_STRING) item.put(name, parser.getString());
                else throw LoginRequestReader.invalid();
            }
            if (!item.keySet().containsAll(Set.of("view", "fileId", "result"))) throw LoginRequestReader.invalid();
            reviews.add(new AdmissionReviewService.PhotoReview(item.get("view"), LoginRequestReader.uuid(item.get("fileId")),
                    item.get("result"), item.get("reasonCode")));
        }
        return List.copyOf(reviews);
    }

    /** At most one entry per catalogue document; the type is taken from the server, never from the agent. */
    private static List<AdmissionReviewService.DocumentReview> documentDecisions(JsonParser parser) {
        List<AdmissionReviewService.DocumentReview> reviews = new ArrayList<>();
        while (parser.nextToken() != JsonToken.END_ARRAY) {
            if (parser.currentToken() != JsonToken.START_OBJECT || reviews.size() >= 12) throw LoginRequestReader.invalid();
            Map<String, String> item = new HashMap<>();
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                String name = parser.getString();
                JsonToken value = parser.nextToken();
                if (!Set.of("documentId", "result", "reasonCode").contains(name) || item.containsKey(name)) throw LoginRequestReader.invalid();
                if (value == JsonToken.VALUE_NULL && "reasonCode".equals(name)) item.put(name, null);
                else if (value == JsonToken.VALUE_STRING) item.put(name, parser.getString());
                else throw LoginRequestReader.invalid();
            }
            if (!item.keySet().containsAll(Set.of("documentId", "result"))) throw LoginRequestReader.invalid();
            reviews.add(new AdmissionReviewService.DocumentReview(LoginRequestReader.uuid(item.get("documentId")), null,
                    item.get("result"), item.get("reasonCode")));
        }
        return List.copyOf(reviews);
    }

    private static <T> ResponseEntity<T> ok(T body) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body); }
    private AdmissionReviewService service() {
        var service = services.getIfAvailable();
        if (service == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        return service;
    }
    private static <T> T invoke(Supplier<T> operation) {
        try { return operation.get(); }
        catch (SessionException error) {
            throw new ResponseStatusException(error.code() == SessionException.Code.SERVICE_UNAVAILABLE
                    ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.UNAUTHORIZED);
        } catch (AdmissionException error) {
            throw DriverApplicationController.admissionError(error);
        }
    }
}
