package com.waka.identity.api;

import com.waka.drivers.SubscriptionException;
import com.waka.drivers.SubscriptionService;
import com.waka.identity.session.SessionException;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** A33 (K32): a private driver buys a 30-day period at 3 000 XAF; it waits for the payment provider. */
@RestController
public final class SubscriptionController {
    private final ObjectProvider<SubscriptionService> services;
    public SubscriptionController(ObjectProvider<SubscriptionService> services) { this.services = services; }

    /** Empty body; Idempotency-Key. The product and its price are the server's, never the phone's. */
    @PostMapping(path = "/api/v1/subscription-purchases", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<SubscriptionService.PeriodView> purchase(HttpServletRequest request) throws IOException {
        UUID key = LoginRequestReader.key(request);
        LoginRequestReader.emptyBody(request);
        char[] token = MeRequestReader.bearer(request);
        try {
            String client = MeRequestReader.client(request);
            return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(invoke(() -> service().purchase(token, client, key)));
        } finally { Arrays.fill(token, '\0'); }
    }

    @GetMapping(path = "/api/v1/driver/subscriptions", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<SubscriptionService.PeriodView>> mine(HttpServletRequest request) throws IOException {
        MeRequestReader.emptyGet(request);
        char[] token = MeRequestReader.bearer(request);
        try {
            String client = MeRequestReader.client(request);
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(invoke(() -> service().mine(token, client)));
        } finally { Arrays.fill(token, '\0'); }
    }

    private SubscriptionService service() {
        var service = services.getIfAvailable();
        if (service == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        return service;
    }
    private static <T> T invoke(java.util.function.Supplier<T> operation) {
        try { return operation.get(); }
        catch (SessionException error) {
            throw new ResponseStatusException(error.code() == SessionException.Code.SERVICE_UNAVAILABLE
                    ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.UNAUTHORIZED);
        } catch (SubscriptionException error) {
            HttpStatus status = switch (error.code()) {
                case INVALID_REQUEST -> HttpStatus.BAD_REQUEST;
                case NOT_PRIVATE_DRIVER -> HttpStatus.FORBIDDEN;
                case TRIAL_NOT_STARTED -> HttpStatus.CONFLICT;
                case NOT_FOUND -> HttpStatus.NOT_FOUND;
            };
            throw new ProfileHttpException(status, error.code() == SubscriptionException.Code.NOT_FOUND ? "RESOURCE_NOT_FOUND" : error.code().name());
        }
    }
}
