package com.waka.identity.api;

import com.waka.identity.profile.PersistentProfileService;
import com.waka.identity.profile.ProfileException;
import com.waka.identity.session.SessionException;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Arrays;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Separate profile read preserves the existing seven-field GET /me contract. */
@RestController
public final class ProfileController {
    private final ObjectProvider<PersistentProfileService> services;
    public ProfileController(ObjectProvider<PersistentProfileService> services) { this.services = services; }

    @GetMapping(path = "/api/v1/me/profile", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PersistentProfileService.ProfileView> read(HttpServletRequest request) throws IOException {
        MeRequestReader.emptyGet(request);
        char[] token = MeRequestReader.bearer(request);
        try {
            String client = MeRequestReader.client(request);
            var view = invoke(() -> service().read(token, client));
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(view);
        } finally { Arrays.fill(token, '\0'); }
    }

    @PatchMapping(path = "/api/v1/me", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PersistentProfileService.ProfileView> patch(HttpServletRequest request) throws IOException {
        UUID key = LoginRequestReader.key(request);
        var input = ProfileRequestReader.patch(request);
        char[] token = MeRequestReader.bearer(request);
        try {
            String client = MeRequestReader.client(request);
            var view = invoke(() -> service().patch(token, client, key, input.expectedVersion(), input.displayName()));
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(view);
        } finally { Arrays.fill(token, '\0'); }
    }

    private PersistentProfileService service() {
        var service = services.getIfAvailable();
        if (service == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        return service;
    }
    private static <T> T invoke(Supplier<T> operation) {
        try { return operation.get(); }
        catch (SessionException error) {
            throw new ResponseStatusException(error.code() == SessionException.Code.SERVICE_UNAVAILABLE
                    ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.UNAUTHORIZED);
        } catch (ProfileException error) {
            HttpStatus status = switch (error.code()) {
                case INVALID_REQUEST -> HttpStatus.BAD_REQUEST;
                case VERSION_CONFLICT, IDEMPOTENCY_KEY_REUSED -> HttpStatus.CONFLICT;
                case REPLAY_EXPIRED -> HttpStatus.GONE;
                case SERVICE_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            };
            throw new ProfileHttpException(status, error.code().name());
        }
    }
}
