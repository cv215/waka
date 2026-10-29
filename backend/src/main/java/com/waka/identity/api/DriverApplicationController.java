package com.waka.identity.api;

import com.waka.identity.admission.AdmissionException;
import com.waka.identity.admission.AdmissionReviewService;
import java.util.List;
import com.waka.identity.admission.PersistentDriverApplicationService;
import com.waka.identity.session.SessionException;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** A26-A28 (vehicle fields only). A draft is never an admission, a driver role or a commercial access. */
@RestController
public final class DriverApplicationController {
    private final ObjectProvider<PersistentDriverApplicationService> services;
    private final ObjectProvider<AdmissionReviewService> reviews;
    public DriverApplicationController(ObjectProvider<PersistentDriverApplicationService> services,
            ObjectProvider<AdmissionReviewService> reviews) {
        this.services = services;
        this.reviews = reviews;
    }

    @PostMapping(path = "/api/v1/driver-applications", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PersistentDriverApplicationService.ApplicationView> create(HttpServletRequest request) throws IOException {
        UUID key = LoginRequestReader.key(request);
        String category = LoginRequestReader.body(request, Set.of("requestedCategory")).get("requestedCategory");
        char[] token = MeRequestReader.bearer(request);
        try {
            String client = MeRequestReader.client(request);
            var view = invoke(() -> service().create(token, client, key, category));
            return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(view);
        } finally { Arrays.fill(token, '\0'); }
    }

    @GetMapping(path = "/api/v1/me/driver-application", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PersistentDriverApplicationService.ApplicationView> current(HttpServletRequest request) throws IOException {
        MeRequestReader.emptyGet(request);
        char[] token = MeRequestReader.bearer(request);
        try {
            String client = MeRequestReader.client(request);
            return ok(invoke(() -> service().readCurrent(token, client)));
        } finally { Arrays.fill(token, '\0'); }
    }

    @GetMapping(path = "/api/v1/driver-applications/{applicationId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PersistentDriverApplicationService.ApplicationView> read(@PathVariable String applicationId,
            HttpServletRequest request) throws IOException {
        UUID id = LoginRequestReader.uuid(applicationId);
        MeRequestReader.emptyGet(request);
        char[] token = MeRequestReader.bearer(request);
        try {
            String client = MeRequestReader.client(request);
            return ok(invoke(() -> service().read(token, client, id)));
        } finally { Arrays.fill(token, '\0'); }
    }

    @PatchMapping(path = "/api/v1/driver-applications/{applicationId}", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PersistentDriverApplicationService.ApplicationView> updateVehicle(@PathVariable String applicationId,
            HttpServletRequest request) throws IOException {
        UUID id = LoginRequestReader.uuid(applicationId);
        UUID key = LoginRequestReader.key(request);
        var input = DriverApplicationRequestReader.vehicle(request);
        char[] token = MeRequestReader.bearer(request);
        try {
            String client = MeRequestReader.client(request);
            return ok(invoke(() -> service().updateVehicle(token, client, id, key, input.expectedVersion(), input.fields())));
        } finally { Arrays.fill(token, '\0'); }
    }

    /** A29: body is exactly {"expectedVersion": n}; refused while document requirements are not qualified. */
    @PostMapping(path = "/api/v1/driver-applications/{applicationId}/submissions", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PersistentDriverApplicationService.ApplicationView> submit(@PathVariable String applicationId,
            HttpServletRequest request) throws IOException {
        UUID id = LoginRequestReader.uuid(applicationId);
        UUID key = LoginRequestReader.key(request);
        long version = DriverApplicationRequestReader.expectedVersionOnly(request);
        char[] token = MeRequestReader.bearer(request);
        try {
            String client = MeRequestReader.client(request);
            return ok(invoke(() -> service().submit(token, client, id, key, version)));
        } finally { Arrays.fill(token, '\0'); }
    }

    /** The decisions and reasons about the caller's own file; the agent is never named. */
    @GetMapping(path = "/api/v1/driver-applications/{applicationId}/decisions", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<AdmissionReviewService.Decision>> decisions(@PathVariable String applicationId,
            HttpServletRequest request) throws IOException {
        UUID id = LoginRequestReader.uuid(applicationId);
        MeRequestReader.emptyGet(request);
        char[] token = MeRequestReader.bearer(request);
        try {
            String client = MeRequestReader.client(request);
            var reviews = this.reviews.getIfAvailable();
            if (reviews == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
            return ok(invoke(() -> reviews.candidateDecisions(token, client, id)));
        } finally { Arrays.fill(token, '\0'); }
    }

    private static <T> ResponseEntity<T> ok(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
    private PersistentDriverApplicationService service() {
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
            throw admissionError(error);
        }
    }

    /** Shared with the examination routes so both sides speak the same safe codes. */
    static ProfileHttpException admissionError(AdmissionException error) {
        HttpStatus status = switch (error.code()) {
            case INVALID_REQUEST -> HttpStatus.BAD_REQUEST;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case APPLICATION_ALREADY_OPEN, VERSION_CONFLICT, IDEMPOTENCY_KEY_REUSED, NOT_EDITABLE, INCOMPLETE,
                 REQUIREMENTS_NOT_QUALIFIED, APPROVAL_NOT_AVAILABLE -> HttpStatus.CONFLICT;
            case REPLAY_EXPIRED -> HttpStatus.GONE;
            case NOT_AUTHORIZED, CONFLICT_OF_INTEREST -> HttpStatus.FORBIDDEN;
            case INVALID_ADMIN_SESSION -> HttpStatus.UNAUTHORIZED;
            case SERVICE_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
        };
        // The 404 keeps the generic code: it must not reveal whether another candidate's file exists.
        String code = switch (error.code()) {
            case NOT_FOUND -> "RESOURCE_NOT_FOUND";
            case NOT_AUTHORIZED -> "ACTION_NOT_ALLOWED";
            default -> error.code().name();
        };
        return new ProfileHttpException(status, code);
    }
}
